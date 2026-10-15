import { exigirCredenciales, leerEntorno } from '../support/entorno.js';
import {
  crearDepartamento,
  datosEmpleado,
  esperarTokenActivacion,
  login,
} from '../support/fabrica.js';
import { pedir } from '../support/http.js';

const CORRIDAS = 10;

function percentil(valores, p) {
  const ordenados = [...valores].sort((a, b) => a - b);
  const indice = Math.min(ordenados.length - 1, Math.ceil((p / 100) * ordenados.length) - 1);
  return ordenados[Math.max(0, indice)];
}

async function unaPropagacion(world) {
  const departamento = await crearDepartamento(world, world.tokenAdmin);
  const empleado = datosEmpleado(departamento.id);
  const alta = await pedir(world, 'POST', '/empleados', { body: empleado, token: world.tokenAdmin });
  if (alta.status !== 201) {
    throw new Error(`Alta ${alta.status}: ${JSON.stringify(alta.cuerpo)}`);
  }
  const inicio = Date.now();
  await esperarTokenActivacion(world, empleado.id, world.tokenAdmin);
  return Date.now() - inicio;
}

const entorno = leerEntorno();
exigirCredenciales(entorno);
// Intervalo fino solo para medir. La suite sigue usando POLL_* del entorno.
const world = {
  entorno: { ...entorno, pollIntentos: 50, pollIntervaloMs: 200 },
  baseUrl: entorno.baseUrl,
  token: null,
  tokenAdmin: null,
  ultimaRespuesta: null,
};

const sesion = await login(world, entorno.adminUsuario, entorno.adminPassword);
world.tokenAdmin = sesion.token;

const tiempos = [];
for (let i = 1; i <= CORRIDAS; i += 1) {
  const ms = await unaPropagacion(world);
  tiempos.push(ms);
  console.log(`corrida ${i}: ${ms} ms`);
}

const maximo = Math.max(...tiempos);
const minimo = Math.min(...tiempos);
const media = Math.round(tiempos.reduce((suma, valor) => suma + valor, 0) / tiempos.length);
console.log(
  `min ${minimo} ms | p50 ${percentil(tiempos, 50)} ms | p95 ${percentil(tiempos, 95)} ms | max ${maximo} ms | media ${media} ms`,
);
console.log(
  `presupuesto actual: ${entorno.pollIntentos} × ${entorno.pollIntervaloMs} ms = ${entorno.pollIntentos * entorno.pollIntervaloMs} ms`,
);
