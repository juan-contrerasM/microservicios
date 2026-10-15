export class ErrorDePolling extends Error {}

export function esperar(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

/**
 * Repite `condicion` hasta que devuelva un valor verdadero.
 * Un valor falsy significa «todavía no»; `ErrorDePolling` aborta en el acto.
 * El resto de los errores se reintentan hasta agotar el presupuesto.
 */
export async function eventualmente(condicion, { intentos = 15, intervaloMs = 2000 } = {}) {
  let ultimo = null;
  for (let i = 1; i <= intentos; i += 1) {
    try {
      const valor = await condicion();
      if (valor) {
        return valor;
      }
      ultimo = new Error('la condición todavía no se cumple');
    } catch (error) {
      if (error instanceof ErrorDePolling) {
        throw error;
      }
      ultimo = error;
    }
    if (i < intentos) {
      await esperar(intervaloMs);
    }
  }
  const detalle = ultimo?.message ? ` Último motivo: ${ultimo.message}` : '';
  throw new Error(`El evento no se procesó tras ${intentos} intentos × ${intervaloMs} ms.${detalle}`);
}
