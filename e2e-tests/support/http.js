export async function pedir(world, method, ruta, opciones = {}) {
  const headers = { accept: 'application/json' };
  if (opciones.body !== undefined) {
    headers['content-type'] = 'application/json';
  }
  const tokenExplicito = Object.hasOwn(opciones, 'token');
  const token = tokenExplicito ? opciones.token : (opciones.auth === false ? null : world.token);
  if (token) {
    headers.authorization = /^Bearer\s+/i.test(token) ? token : `Bearer ${token}`;
  }

  const url = `${world.baseUrl}${ruta}`;
  let respuesta;
  try {
    respuesta = await fetch(url, {
      method,
      headers,
      body: opciones.body !== undefined ? JSON.stringify(opciones.body) : undefined,
    });
  } catch (error) {
    world.ultimaRespuesta = null;
    throw new Error(`No hubo respuesta de ${method} ${url}: ${error.message}`);
  }

  const texto = await respuesta.text();
  let cuerpo = null;
  if (texto.length > 0) {
    try {
      cuerpo = JSON.parse(texto);
    } catch {
      cuerpo = texto;
    }
  }
  world.ultimaRespuesta = { status: respuesta.status, cuerpo };
  return world.ultimaRespuesta;
}
