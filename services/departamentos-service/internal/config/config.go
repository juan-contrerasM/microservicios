// Package config carga la configuración del servicio desde variables de
// entorno. No hay archivo de configuración: en Docker Compose todo llega por
// entorno (ver docker-compose.yml raíz y .env.example).
package config

import (
	"fmt"
	"os"
	"strings"
)

// Config son todos los parámetros configurables del servicio. Ver
// docker-compose.yml raíz y .env.example para los valores usados en Docker.
type Config struct {
	Port       string // puerto HTTP en el que escucha el servicio
	DBHost     string // host de MySQL (nombre del servicio Compose dentro de Docker)
	DBPort     string // puerto de MySQL
	DBName     string // nombre de la base de datos
	DBUser     string // usuario de la base de datos
	DBPassword string // contraseña de la base de datos
}

// Load lee la configuración de variables de entorno, con valores por
// defecto pensados para correr el servicio en local sin Docker.
func Load() Config {
	return Config{
		Port:       getEnv("PORT", "8081"),
		DBHost:     getEnv("DB_HOST", "localhost"),
		DBPort:     getEnv("DB_PORT", "3306"),
		DBName:     getEnv("DB_NAME", "departamentos_db"),
		DBUser:     getEnv("DB_USER", "departamentos"),
		DBPassword: getSecret("DB_PASSWORD", ""),
	}
}

// getSecret acepta la variable tradicional o KEY_FILE para Docker Secrets.
// El contenido nunca se registra y se eliminan únicamente saltos de línea externos.
func getSecret(key, fallback string) string {
	if value := getEnv(key, ""); value != "" {
		return value
	}
	if path := getEnv(key+"_FILE", ""); path != "" {
		value, err := os.ReadFile(path)
		if err != nil {
			panic(fmt.Sprintf("no se pudo leer %s_FILE: %v", key, err))
		}
		if secret := strings.TrimSpace(string(value)); secret != "" {
			return secret
		}
	}
	return fallback
}

// DSN arma el Data Source Name para el driver go-sql-driver/mysql.
func (c Config) DSN() string {
	return fmt.Sprintf("%s:%s@tcp(%s:%s)/%s?parseTime=true", c.DBUser, c.DBPassword, c.DBHost, c.DBPort, c.DBName)
}

// getEnv devuelve la variable de entorno key, o fallback si no está definida
// o está vacía.
func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}
