# language: es
Característica: Onboarding asíncrono

  Escenario: El empleado recibe el enlace, activa la cuenta e inicia sesión
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento de prueba
    Cuando registro un empleado nuevo
    Entonces la respuesta debe tener código 201
    Y eventualmente llega una notificación "SEGURIDAD" con un token de activación
    Cuando ese empleado intenta iniciar sesión antes de activar la cuenta
    Entonces la respuesta debe tener código 401
    Y el mensaje debe ser "Credenciales inválidas"
    Cuando establece la contraseña con el token de activación
    Entonces la respuesta debe tener código 200
    Y el mensaje debe ser "Contraseña actualizada"
    Cuando ese empleado inicia sesión
    Entonces la respuesta debe tener código 200
    Y el rol de la sesión es "USER"

  Esquema del escenario: El alta rechaza datos que no cumplen el contrato
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento de prueba
    Cuando intento registrar un empleado con "<caso>"
    Entonces la respuesta debe tener código <codigo>
    Y el cuerpo indica "<fragmento>"

    Ejemplos:
      | caso                       | codigo | fragmento      |
      | departamento inexistente   | 400    | no existe      |
      | campo obligatorio faltante | 400    | obligatorio    |
      | email mal formado          | 400    | formato válido |
      | email duplicado            | 400    | ese email      |
