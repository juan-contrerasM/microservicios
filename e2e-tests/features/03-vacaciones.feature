# language: es
Característica: Ciclo de vacaciones

  Antecedentes:
    Dado que existe un empleado con credenciales
    Y que estoy autenticado como "ADMIN"

  Escenario: Programar vacaciones futuras
    Cuando programo vacaciones futuras para el empleado de prueba
    Entonces la respuesta debe tener código 201
    Y el período queda en estado "PROGRAMADA"
    Y eventualmente recibe una notificación "VACACIONES" que contiene "han sido programadas"

  Escenario: Rechazar un rango de fechas invertido
    Cuando intento programar vacaciones con fecha fin anterior al inicio
    Entonces la respuesta debe tener código 400
    Y el mensaje debe ser "La fechaFin no puede ser anterior a la fechaInicio"

  Escenario: Rechazar vacaciones solapadas
    Dado que el empleado tiene vacaciones futuras programadas
    Cuando intento programar otro período que se solapa
    Entonces la respuesta debe tener código 400
    Y el cuerpo indica "se solapa"

  Escenario: Suspender y reactivar la cuenta durante el ciclo completo
    Cuando programo vacaciones futuras para el empleado de prueba
    Entonces la respuesta debe tener código 201
    Cuando fuerzo el inicio de las vacaciones
    Entonces la respuesta debe tener código 200
    Y el período queda en estado "EN_CURSO"
    Y eventualmente el empleado no puede iniciar sesión porque la cuenta no está activa
    Y eventualmente recibe una notificación "CUENTA" que contiene "Su cuenta fue desactivada"
    Cuando fuerzo el fin de las vacaciones
    Entonces la respuesta debe tener código 200
    Y el período queda en estado "FINALIZADA"
    Y eventualmente el empleado puede iniciar sesión nuevamente
    Y eventualmente recibe una notificación "CUENTA" que contiene "Bienvenido de regreso. Su cuenta ha sido reactivada"
