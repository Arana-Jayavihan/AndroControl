{
  description = "AndroControl - Secure remote control for Linux from Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        packages = {
          androcontrol = pkgs.buildGoModule {
            pname = "androcontrol";
            version = "1.1.0";

            src = ./Backend-GO;

            vendorHash = null; # Uses vendored dependencies

            # Unit tests are pure logic (no uinput / network), so run them at build time.
            doCheck = true;

            meta = with pkgs.lib; {
              description = "Secure remote mouse/keyboard control server for Linux";
              homepage = "https://github.com/AranaJayavihan/AndroControl";
              license = licenses.mit;
              platforms = platforms.linux;
              mainProgram = "AndroControl";
            };
          };

          default = self.packages.${system}.androcontrol;
        };

        apps = {
          androcontrol = flake-utils.lib.mkApp {
            drv = self.packages.${system}.androcontrol;
            name = "AndroControl";
          };
          default = self.apps.${system}.androcontrol;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go_1_23
          ];
        };
      }
    ) // {
      # NixOS module
      nixosModules = {
        androcontrol = { config, lib, pkgs, ... }:
          let
            cfg = config.services.androcontrol;

            # Convenience CLI so admins don't have to hand-roll the
            # `runuser ... -data-dir ... && systemctl reload` dance.
            # Usage:  sudo androcontrol-ctl {qr | regen-token | list | revoke <id|name> | revoke-all | cleanup}
            adminCli = pkgs.writeShellScriptBin "androcontrol-ctl" ''
              set -eu

              BIN=${cfg.package}/bin/AndroControl
              DATADIR=${cfg.dataDir}
              SVCUSER=${cfg.user}
              RUNUSER=${pkgs.util-linux}/bin/runuser
              SYSTEMCTL=${pkgs.systemd}/bin/systemctl

              # Re-run with privileges if not already root.
              if [ "$(id -u)" -ne 0 ]; then
                exec /run/wrappers/bin/sudo "$0" "$@"
              fi

              run() { "$RUNUSER" -u "$SVCUSER" -- "$BIN" -data-dir "$DATADIR" "$@"; }
              reload() { "$SYSTEMCTL" reload androcontrol 2>/dev/null || "$SYSTEMCTL" restart androcontrol; }

              cmd="''${1:-}"
              case "$cmd" in
                qr|show-qr)
                  run -show-qr
                  ;;
                regen-token)
                  run -regen-token
                  reload
                  ;;
                list|list-devices)
                  run -list-devices
                  ;;
                revoke)
                  shift
                  [ "$#" -ge 1 ] || { echo "usage: androcontrol-ctl revoke <id|name>" >&2; exit 1; }
                  run -revoke "$1"
                  reload
                  ;;
                revoke-all)
                  run -revoke-all
                  reload
                  ;;
                cleanup)
                  run -cleanup
                  reload
                  ;;
                rename)
                  shift
                  [ "$#" -ge 2 ] || { echo "usage: androcontrol-ctl rename <id> <new-name>" >&2; exit 1; }
                  id="$1"; shift
                  run -rename "$id" -name "$*"
                  reload
                  ;;
                prune-inactive)
                  shift
                  [ "$#" -ge 1 ] || { echo "usage: androcontrol-ctl prune-inactive <days>" >&2; exit 1; }
                  run -prune-inactive "$1"
                  reload
                  ;;
                *)
                  echo "usage: androcontrol-ctl {qr | regen-token | list | revoke <id|name> | revoke-all | cleanup | rename <id> <name> | prune-inactive <days>}" >&2
                  exit 1
                  ;;
              esac
            '';
          in
          {
            options.services.androcontrol = {
              enable = lib.mkEnableOption "AndroControl remote control server";

              package = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${pkgs.system}.androcontrol;
                description = "The AndroControl package to use";
              };

              port = lib.mkOption {
                type = lib.types.port;
                default = 5050;
                description = "Port to listen on";
              };

              bindAddress = lib.mkOption {
                type = lib.types.str;
                default = "0.0.0.0";
                description = ''
                  Address to bind to. Use "127.0.0.1" to restrict to loopback
                  (e.g. when exposing only over a VPN or SSH tunnel).
                '';
              };

              dataDir = lib.mkOption {
                type = lib.types.path;
                default = "/var/lib/androcontrol";
                description = "Directory for TLS certificates, the enrollment token, and the paired-device registry (devices.json)";
              };

              openFirewall = lib.mkOption {
                type = lib.types.bool;
                default = false;
                description = "Whether to open the firewall port";
              };

              user = lib.mkOption {
                type = lib.types.str;
                default = "androcontrol";
                description = "User to run the service as";
              };

              group = lib.mkOption {
                type = lib.types.str;
                default = "androcontrol";
                description = "Group to run the service as";
              };

              desktopNotifications = lib.mkEnableOption ''
                desktop notifications on device connect/disconnect. Adds a per-user
                systemd service that tails the AndroControl journal and pops notify-send
                in the graphical session (the server itself is sandboxed and can't reach
                the session bus). Each desktop user must be able to read the service
                journal (member of the systemd-journal/wheel/adm group)'';

              clipboardSync = lib.mkEnableOption ''
                bidirectional clipboard sync with the paired phone. Adds a loopback
                clipboard relay to the server and a per-user session agent
                (androcontrol-clip) that bridges the system clipboard — Wayland via
                wl-clipboard or X11 via xclip — with the device. Loopback-only'';

              clipPort = lib.mkOption {
                type = lib.types.port;
                default = 5051;
                description = "Loopback port for the clipboard relay (used only when clipboardSync is enabled)";
              };
            };

            config = lib.mkIf cfg.enable {
              # Provides `androcontrol-ctl` on PATH for device management.
              environment.systemPackages = [ adminCli ];

              users.users.${cfg.user} = {
                isSystemUser = true;
                group = cfg.group;
                home = cfg.dataDir;
                extraGroups = [ "input" ]; # Required for uinput access
              };

              users.groups.${cfg.group} = { };

              # Ensure uinput module is loaded
              boot.kernelModules = [ "uinput" ];

              # Allow uinput access for the input group
              services.udev.extraRules = ''
                KERNEL=="uinput", GROUP="input", MODE="0660"
              '';

              systemd.services.androcontrol = {
                description = "AndroControl Remote Control Server";
                wantedBy = [ "multi-user.target" ];
                after = [ "network.target" ];

                serviceConfig = {
                  Type = "simple";
                  User = cfg.user;
                  Group = cfg.group;
                  WorkingDirectory = cfg.dataDir;
                  ExecStart = "${cfg.package}/bin/AndroControl -addr ${cfg.bindAddress} -port ${toString cfg.port}"
                    + lib.optionalString cfg.clipboardSync " -clip-port ${toString cfg.clipPort}";
                  # `systemctl reload androcontrol` re-reads devices.json so external
                  # revoke/cleanup changes apply without a full restart.
                  ExecReload = "${pkgs.coreutils}/bin/kill -HUP $MAINPID";
                  Restart = "on-failure";
                  RestartSec = 5;

                  # Hardening
                  NoNewPrivileges = true;
                  ProtectSystem = "strict";
                  ProtectHome = true;
                  PrivateTmp = true;
                  ProtectControlGroups = true;
                  ProtectKernelTunables = true;
                  RestrictAddressFamilies = [ "AF_INET" "AF_INET6" ];
                  ReadWritePaths = [ cfg.dataDir ];

                  # Required for uinput: only allow the uinput device node.
                  SupplementaryGroups = [ "input" ];
                  DevicePolicy = "closed";
                  DeviceAllow = [ "/dev/uinput rw" ];
                };

                preStart = ''
                  mkdir -p ${cfg.dataDir}/certs
                '';
              };

              systemd.tmpfiles.rules = [
                "d ${cfg.dataDir} 0750 ${cfg.user} ${cfg.group} -"
                "d ${cfg.dataDir}/certs 0750 ${cfg.user} ${cfg.group} -"
              ];

              # Per-user desktop notifications: a session service that watches the
              # AndroControl journal and pops notify-send on connect/disconnect.
              systemd.user.services.androcontrol-notify = lib.mkIf cfg.desktopNotifications {
                description = "AndroControl connect/disconnect desktop notifications";
                wantedBy = [ "graphical-session.target" ];
                partOf = [ "graphical-session.target" ];
                after = [ "graphical-session.target" ];
                path = [ pkgs.systemd pkgs.gnused pkgs.libnotify ];
                serviceConfig = {
                  Restart = "always";
                  RestartSec = 5;
                };
                script = ''
                  journalctl -u androcontrol -f -n0 -o cat | while IFS= read -r line; do
                    case "$line" in
                      *"[AUDIT] auth_ok "*|*"[AUDIT] pair_ok "*)
                        dev=$(printf '%s' "$line" | sed -n 's/.*device="\([^"]*\)".*/\1/p')
                        [ -n "$dev" ] || dev="New device"
                        ip=$(printf '%s' "$line" | sed -n 's/.*ip=\([0-9.]*\).*/\1/p')
                        notify-send -a AndroControl "Device connected" "$dev ($ip)" || true
                        ;;
                      *"[AUDIT] disconnect "*)
                        dev=$(printf '%s' "$line" | sed -n 's/.*device="\([^"]*\)".*/\1/p')
                        notify-send -a AndroControl "Device disconnected" "$dev" || true
                        ;;
                    esac
                  done
                '';
              };

              # Per-user clipboard sync agent: bridges the session clipboard with the
              # server's loopback relay (the sandboxed server can't reach the display
              # server itself). Uses wl-clipboard on Wayland or xclip on X11.
              systemd.user.services.androcontrol-clip = lib.mkIf cfg.clipboardSync {
                description = "AndroControl clipboard sync agent";
                wantedBy = [ "graphical-session.target" ];
                partOf = [ "graphical-session.target" ];
                after = [ "graphical-session.target" ];
                path = [ pkgs.wl-clipboard pkgs.xclip ];
                environment.ANDROCONTROL_CLIP_PORT = toString cfg.clipPort;
                serviceConfig = {
                  ExecStart = "${cfg.package}/bin/androcontrol-clip";
                  Restart = "always";
                  RestartSec = 5;
                };
              };

              networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
            };
          };

        default = self.nixosModules.androcontrol;
      };

      # Overlay for adding to pkgs
      overlays.default = final: prev: {
        androcontrol = self.packages.${prev.system}.androcontrol;
      };
    };
}
