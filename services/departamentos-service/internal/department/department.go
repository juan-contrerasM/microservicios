// Package department contiene el modelo, errores de dominio y acceso a datos
// del servicio de departamentos.
package department

import "errors"

// Department es el modelo canónico definido en el Reto 2 (§3).
type Department struct {
	ID          string `json:"id"`          // identificador único, también PRIMARY KEY en la tabla
	Nombre      string `json:"nombre"`      // nombre del departamento, obligatorio
	Descripcion string `json:"descripcion"` // descripción libre, opcional
}

var (
	// ErrAlreadyExists se devuelve al intentar registrar un id ya usado.
	// La garantía real es la restricción UNIQUE del esquema (ver
	// db/migrations/0001_create_departamentos.up.sql); esta comprobación
	// en código solo sirve para dar un mensaje 400 descriptivo en el caso
	// común, no para garantizar la unicidad bajo concurrencia.
	ErrAlreadyExists = errors.New("ya existe un departamento registrado con ese id")
	// ErrNotFound se devuelve cuando el departamento no existe.
	ErrNotFound = errors.New("el departamento no existe")
)
