exports.up = (pgm) => {
	pgm.createTable('empleados_replica', {
		empleado_id: { type: 'varchar(64)', primaryKey: true },
		email: { type: 'varchar(255)', notNull: true },
		estado: { type: 'varchar(32)', notNull: true },
	});
	pgm.createTable('secuencias', {
		anio: { type: 'integer', primaryKey: true },
		ultimo: { type: 'integer', notNull: true },
	});
	pgm.createTable('vacaciones', {
		id: { type: 'varchar(20)', primaryKey: true },
		empleado_id: { type: 'varchar(64)', notNull: true },
		fecha_inicio: { type: 'date', notNull: true },
		fecha_fin: { type: 'date', notNull: true },
		estado: { type: 'varchar(32)', notNull: true },
		fecha_creacion: { type: 'timestamptz', notNull: true },
		dias_habiles: { type: 'integer', notNull: true },
	});
	pgm.createIndex('vacaciones', 'empleado_id');
	pgm.createTable('eventos_procesados', {
		id: { type: 'varchar(64)', primaryKey: true },
		procesado_en: { type: 'timestamptz', notNull: true },
	});
};

exports.down = (pgm) => {
	pgm.dropTable('eventos_procesados');
	pgm.dropTable('vacaciones');
	pgm.dropTable('secuencias');
	pgm.dropTable('empleados_replica');
};
