# language: es
@caos
Característica: Resiliencia del borde

  Escenario: El circuito del Gateway abre con Departamentos caído y se recupera solo
    Dado que estoy autenticado como "ADMIN"
    Y que el contenedor de "departamentos-service" está detenido
    Cuando consulto el listado de departamentos 3 veces
    Entonces la respuesta debe tener código 503
    Y el mensaje debe ser "El servicio de departamentos no está disponible"
    Y el circuito del Gateway hacia "departamentos-service" está abierto
    Cuando el contenedor de "departamentos-service" vuelve a arrancar
    Entonces eventualmente el listado de departamentos responde 200
    Y el circuito del Gateway hacia "departamentos-service" está cerrado
