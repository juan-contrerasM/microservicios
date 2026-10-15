# language: es
Característica: Retiro definitivo de empleados

  Antecedentes:
    Dado que existe un empleado con credenciales
    Y que estoy autenticado como "ADMIN"

  Escenario: Retirar conserva auditoría y bloquea la cuenta
    Cuando el administrador retira al empleado de prueba
    Entonces la respuesta debe tener código 200
    Y el empleado queda en estado "RETIRADO"
    Y eventualmente recibe la notificación de cuenta desactivada
    Y eventualmente el retiro bloquea el inicio de sesión
    Cuando consulto la auditoría de empleados retirados
    Entonces la respuesta debe tener código 200
    Y la auditoría contiene al empleado retirado

  Escenario: La recuperación no vuelve a habilitar una cuenta retirada
    Dado que el empleado tiene un token de recuperación vigente
    Cuando el administrador retira al empleado de prueba
    Entonces la respuesta debe tener código 200
    Y eventualmente el retiro bloquea el inicio de sesión
    Cuando solicito recuperar la cuenta retirada
    Entonces la respuesta debe tener código 200
    Y el mensaje debe ser "Si el correo existe, se envió un enlace de recuperación"
    Cuando intento cambiar la contraseña retirada con el token vigente
    Entonces la respuesta debe tener código 400
    Y el mensaje debe ser "La cuenta no admite un cambio de contraseña en su estado actual"
