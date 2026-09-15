-- golang-migrate versión 0001 (down): rollback de 0001_create_departamentos.up.sql.
-- Borra la tabla completa; solo pensado para revertir esta migración en un
-- ambiente de desarrollo, no para producción con datos que importen.
DROP TABLE IF EXISTS departamentos;
