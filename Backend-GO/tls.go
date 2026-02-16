package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"log"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

const (
	CertDir      = "certs"
	CertFile     = "server.crt"
	KeyFile      = "server.key"
	CertValidity = 365 * 24 * time.Hour // 1 year
)

// TLSConfig holds TLS configuration
type TLSConfig struct {
	CertPath string
	KeyPath  string
}

// NewTLSConfig creates a new TLS config with default paths
func NewTLSConfig() *TLSConfig {
	return &TLSConfig{
		CertPath: filepath.Join(CertDir, CertFile),
		KeyPath:  filepath.Join(CertDir, KeyFile),
	}
}

// EnsureCertificates checks for existing certs or generates new ones
func (tc *TLSConfig) EnsureCertificates() error {
	// Check if certificates exist
	if tc.certificatesExist() {
		log.Println("Using existing TLS certificates")
		return nil
	}

	log.Println("Generating new self-signed TLS certificates...")
	return tc.generateCertificates()
}

// certificatesExist checks if both cert and key files exist
func (tc *TLSConfig) certificatesExist() bool {
	_, certErr := os.Stat(tc.CertPath)
	_, keyErr := os.Stat(tc.KeyPath)
	return certErr == nil && keyErr == nil
}

// generateCertificates creates new self-signed certificates
func (tc *TLSConfig) generateCertificates() error {
	// Create certs directory if it doesn't exist
	if err := os.MkdirAll(CertDir, 0700); err != nil {
		return fmt.Errorf("failed to create certs directory: %w", err)
	}

	// Generate private key using ECDSA P-256
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("failed to generate private key: %w", err)
	}

	// Generate serial number
	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return fmt.Errorf("failed to generate serial number: %w", err)
	}

	// Get local IPs for SAN
	ips, err := getLocalIPs()
	if err != nil {
		log.Printf("Warning: Could not get local IPs: %v", err)
		ips = []net.IP{net.ParseIP("127.0.0.1")}
	}

	// Create certificate template
	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"AndroControl"},
			CommonName:   "AndroControl Server",
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().Add(CertValidity),
		KeyUsage:              x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IPAddresses:           ips,
		DNSNames:              []string{"localhost", "androcontrol.local"},
	}

	// Create certificate
	certDER, err := x509.CreateCertificate(rand.Reader, &template, &template, &privateKey.PublicKey, privateKey)
	if err != nil {
		return fmt.Errorf("failed to create certificate: %w", err)
	}

	// Write certificate to file
	certFile, err := os.Create(tc.CertPath)
	if err != nil {
		return fmt.Errorf("failed to create cert file: %w", err)
	}
	defer certFile.Close()

	if err := pem.Encode(certFile, &pem.Block{Type: "CERTIFICATE", Bytes: certDER}); err != nil {
		return fmt.Errorf("failed to write cert: %w", err)
	}

	// Write private key to file
	keyFile, err := os.OpenFile(tc.KeyPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return fmt.Errorf("failed to create key file: %w", err)
	}
	defer keyFile.Close()

	keyDER, err := x509.MarshalECPrivateKey(privateKey)
	if err != nil {
		return fmt.Errorf("failed to marshal private key: %w", err)
	}

	if err := pem.Encode(keyFile, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}); err != nil {
		return fmt.Errorf("failed to write key: %w", err)
	}

	log.Printf("TLS certificates generated successfully")
	log.Printf("  Certificate: %s", tc.CertPath)
	log.Printf("  Private Key: %s", tc.KeyPath)

	return nil
}

// LoadTLSConfig loads the TLS configuration for the server
func (tc *TLSConfig) LoadTLSConfig() (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(tc.CertPath, tc.KeyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load TLS certificates: %w", err)
	}

	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		CipherSuites: []uint16{
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
		},
	}, nil
}

// GetCertificateFingerprint returns the full SHA256 fingerprint of the certificate
func (tc *TLSConfig) GetCertificateFingerprint() (string, error) {
	certPEM, err := os.ReadFile(tc.CertPath)
	if err != nil {
		return "", fmt.Errorf("failed to read certificate: %w", err)
	}

	block, _ := pem.Decode(certPEM)
	if block == nil {
		return "", fmt.Errorf("failed to decode PEM block")
	}

	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return "", fmt.Errorf("failed to parse certificate: %w", err)
	}

	// Calculate full SHA256 fingerprint of certificate
	hash := sha256.Sum256(cert.Raw)
	fingerprint := hex.EncodeToString(hash[:])
	return fingerprint, nil
}

// getLocalIPs returns all local IP addresses
func getLocalIPs() ([]net.IP, error) {
	var ips []net.IP

	// Always include localhost
	ips = append(ips, net.ParseIP("127.0.0.1"))
	ips = append(ips, net.ParseIP("::1"))

	interfaces, err := net.Interfaces()
	if err != nil {
		return ips, err
	}

	for _, iface := range interfaces {
		// Skip loopback and down interfaces
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}

			if ip != nil && !ip.IsLoopback() {
				ips = append(ips, ip)
			}
		}
	}

	return ips, nil
}

// PrintCertificateInfo displays certificate info for the user
func (tc *TLSConfig) PrintCertificateInfo() {
	fingerprint, err := tc.GetCertificateFingerprint()
	if err != nil {
		log.Printf("Warning: Could not get certificate fingerprint: %v", err)
		return
	}

	log.Println("=== TLS Certificate Info ===")
	log.Printf("SHA-256 Fingerprint: %s", fingerprint)
	log.Println("When connecting for the first time, verify this fingerprint matches")
	log.Println("============================")
}
