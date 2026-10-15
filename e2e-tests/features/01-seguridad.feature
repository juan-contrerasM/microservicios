# language: es
Característica: Seguridad y control de acceso

  Antecedentes:
    Dado que existe un empleado con credenciales
    Y que existe un departamento de prueba

  Escenario: Consultar empleados sin token
    Cuando consulto el listado de empleados sin autenticación
    Entonces la respuesta debe tener código 401
    Y el mensaje debe ser "No autenticado"

  Escenario: Un token malformado no autentica
    Cuando consulto el listado de empleados con el token "abc"
    Entonces la respuesta debe tener código 401
    Y el mensaje debe ser "No autenticado"

  Escenario: Un token con la firma alterada no autentica
    Dado un token de acceso con la firma alterada
    Cuando consulto el listado de empleados con ese token
    Entonces la respuesta debe tener código 401
    Y el mensaje debe ser "No autenticado"

  Escenario: Un usuario consulta empleados y no puede crearlos ni retirarlos
    Dado que estoy autenticado como "USER"
    Cuando consulto el listado de empleados
    Entonces la respuesta debe tener código 200
    Y el cuerpo es una lista
    Cuando registro un empleado nuevo
    Entonces la respuesta debe tener código 403
    Y el mensaje debe ser "No tiene permisos para realizar esta operación"
    Cuando retiro al empleado de prueba
    Entonces la respuesta debe tener código 403
    Y el mensaje debe ser "No tiene permisos para realizar esta operación"

  Escenario: Un administrador crea un empleado y lo consulta
    Dado que estoy autenticado como "ADMIN"
    Cuando registro un empleado nuevo
    Entonces la respuesta debe tener código 201
    Cuando consulto ese empleado
    Entonces la respuesta debe tener código 200
    Y la respuesta corresponde al empleado registrado

  Escenario: Un usuario edita su perfil y no el de otro
    Dado que estoy autenticado como "ADMIN"
    Y que registro otro empleado
    Dado que estoy autenticado como "USER"
    Y que el perfil del empleado de prueba ya existe
    Cuando edito el perfil del empleado de prueba
    Entonces la respuesta debe tener código 200
    Y el teléfono del perfil es "3001112233"
    Cuando edito el perfil del otro empleado
    Entonces la respuesta debe tener código 403
    Y el mensaje debe ser "No tiene permisos para realizar esta operación"
