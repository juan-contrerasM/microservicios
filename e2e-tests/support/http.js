const REINTENTOS_RED = 3;

function detalleDeRed(error) {
  const codigo = error?.cause?.code || error?.cause?.message;
  return codigo ? `${error.message} (${codigo})` : error.message;
}

// Un corte de TCP contra el Gateway (fetch failed) no es un 4xx/5xx.
// Reintentar solo ese caso evita marcar el build inestable por un paquete perdido.
async function fetchConReintento(url, init) {
  let ultimo;
  for (let intento = 1; intento <= REINTENTOS_RED; intento += 1) {
    try {
      return await fetch(url, init);
    } catch (error) {
      ultimo = error;
      if (intento === REINTENTOS_RED) break;
      await new Promise((resolver) => setTimeout(resolver, 500 * intento));
    }
  }
  throw ultimo;
}

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
    respuesta = await fetchConReintento(url, {
      method,
      headers,
      body: opciones.body !== undefined ? JSON.stringify(opciones.body) : undefined,
    });
  } catch (error) {
    world.ultimaRespuesta = null;
    throw new Error(`No hubo respuesta de ${method} ${url}: ${detalleDeRed(error)}`);
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
