import { Before, BeforeAll, setDefaultTimeout } from '@cucumber/cucumber';
import { leerEntorno } from './entorno.js';

setDefaultTimeout(120_000);

BeforeAll(async function () {
  const { baseUrl } = leerEntorno();
  let respuesta;
  try {
    respuesta = await fetch(`${baseUrl}/health`);
  } catch (error) {
    throw new Error(
      `El Gateway no responde en ${baseUrl}/health. Levanta el sistema con docker compose up. ${error.message}`,
    );
  }
  if (respuesta.status !== 200) {
    throw new Error(`El Gateway respondió ${respuesta.status} en /health`);
  }
});

Before(function () {
  this.token = null;
  this.ultimaRespuesta = null;
});
