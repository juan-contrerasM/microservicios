# language: es
Característica: Gestión de departamentos

  Escenario: Un administrador registra, edita y elimina un departamento
    Dado que estoy autenticado como "ADMIN"
    Cuando registro un departamento nuevo
    Entonces la respuesta debe tener código 201
    Cuando registro de nuevo el mismo departamento
    Entonces la respuesta debe tener código 409
    Cuando renombro el departamento a "Plataforma"
    Entonces la respuesta debe tener código 200
    Cuando consulto el departamento
    Entonces la respuesta debe tener código 200
    Y el nombre del departamento es "Plataforma"
    Cuando elimino el departamento
    Entonces la respuesta debe tener código 204
    Cuando consulto el departamento
    Entonces la respuesta debe tener código 404

  Escenario: Un usuario consulta departamentos y no puede crearlos
    Dado que existe un empleado con credenciales
    Y que estoy autenticado como "USER"
    Cuando consulto el listado de departamentos
    Entonces la respuesta debe tener código 200
    Y el cuerpo es una lista
    Cuando registro un departamento nuevo
    Entonces la respuesta debe tener código 403
    Y el mensaje debe ser "No tiene permisos para realizar esta operación"
