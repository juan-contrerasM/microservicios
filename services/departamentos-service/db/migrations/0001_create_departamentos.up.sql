-- golang-migrate versión 0001 (up): crea la tabla departamentos.
-- Contraparte de rollback: 0001_create_departamentos.down.sql
CREATE TABLE IF NOT EXISTS departamentos (
    id          VARCHAR(50)  NOT NULL, -- PRIMARY KEY: es la garantía real de unicidad, no solo la consulta previa desde el código
    nombre      VARCHAR(150) NOT NULL,
    descripcion VARCHAR(500) NOT NULL DEFAULT '',
    creado_en   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, -- auditoría básica, no expuesto en la API
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
