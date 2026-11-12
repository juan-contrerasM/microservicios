# language: es
@broker
Característica: Eventos idempotentes y mensajes inválidos

  Escenario: Un empleado.creado republicado no duplica la cuenta, el perfil ni el correo
    Dado que estoy autenticado como "ADMIN"
    Cuando se publica dos veces el mismo evento empleado.creado de un empleado nuevo
    Entonces eventualmente existe exactamente una notificación "SEGURIDAD" para ese empleado
    Y existe exactamente un perfil para ese empleado

  Escenario: Un mensaje que no es JSON termina en la DLQ sin bloquear la cola
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento de prueba
    Cuando se publica un mensaje que no es JSON con la clave "empleado.creado"
    Entonces eventualmente las DLQ de auth, perfiles, notificaciones y vacaciones reciben el mensaje
    Cuando registro un empleado nuevo
    Entonces la respuesta debe tener código 201
    Y eventualmente llega una notificación "SEGURIDAD" con un token de activación
