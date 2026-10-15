import { randomBytes } from 'node:crypto';
import { exigirCredenciales } from './entorno.js';
import { pedir } from './http.js';
import { ErrorDePolling, eventualmente } from './polling.js';
import { extraerToken } from './tokens.js';

export function sufijo() {
  return `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;
}

export function datosDepartamento() {
  const id = `d${sufijo()}`;
  return {
    id,
    nombre: `Departamento ${id}`,
    descripcion: 'Creado por la suite BDD',
  };
}

export function datosEmpleado(departamentoId, cambios = {}) {
  const id = `e${sufijo()}`;
  return {
    id,
    nombre: 'Ana',
    apellido: 'Prueba',
    email: `${id}@example.com`,
    numeroEmpleado: `EMP-${id}`,
    cargo: 'Analista',
    area: 'Tecnología',
    departamentoId,
    fechaIngreso: '2026-02-10',
    estado: 'ACTIVO',
    ...cambios,
  };
}

export function fechaUtcMasDias(dias) {
  const fecha = new Date();
  fecha.setUTCHours(0, 0, 0, 0);
  fecha.setUTCDate(fecha.getUTCDate() + dias);
  return fecha.toISOString().slice(0, 10);
}

export async function login(world, usuario, contrasena) {
  const respuesta = await pedir(world, 'POST', '/auth/login', {
    auth: false,
    body: { usuario, contrasena },
  });
  if (respuesta.status !== 200 || !respuesta.cuerpo?.token) {
    throw new Error(`Login de ${usuario} falló: ${respuesta.status} ${JSON.stringify(respuesta.cuerpo)}`);
  }
  return respuesta.cuerpo;
}

export async function crearDepartamento(world, token) {
  const departamento = datosDepartamento();
  const respuesta = await pedir(world, 'POST', '/departamentos', { body: departamento, token });
  if (respuesta.status !== 201) {
    throw new Error(
      `No se pudo crear el departamento ${departamento.id}: ${respuesta.status} ${JSON.stringify(respuesta.cuerpo)}`,
    );
  }
  return departamento;
}

export async function esperarNotificacion(world, empleadoId, token, coincide) {
  return eventualmente(async () => {
    const respuesta = await pedir(
      world,
      'GET',
      `/notificaciones/${encodeURIComponent(empleadoId)}`,
      { token },
    );
    if (respuesta.status !== 200) {
      throw new ErrorDePolling(
        `GET /notificaciones/${empleadoId} respondió ${respuesta.status} ${JSON.stringify(respuesta.cuerpo)}`,
      );
    }
    const lista = Array.isArray(respuesta.cuerpo) ? respuesta.cuerpo : [];
    return lista.find(coincide) ?? null;
  }, {
    intentos: world.entorno.pollIntentos,
    intervaloMs: world.entorno.pollIntervaloMs,
  });
}

export async function esperarTokenActivacion(world, empleadoId, token, tokenExcluido = null) {
  const aviso = await esperarNotificacion(
    world,
    empleadoId,
    token,
    (item) => {
      const encontrado = item?.tipo === 'SEGURIDAD' ? extraerToken(item.mensaje) : null;
      return encontrado && encontrado !== tokenExcluido;
    },
  );
  return extraerToken(aviso.mensaje);
}

/**
 * Onboarding real de un USER: departamento, empleado, token leído de
 * GET /notificaciones/{id}, reset-password y login. Cada llamada usa ids
 * nuevos. No borra datos y no pisa `world.token`: quien llama decide
 * con qué rol sigue el escenario.
 */
export async function empleadoConCredenciales(world) {
  exigirCredenciales(world.entorno);
  if (!world.tokenAdmin) {
    const sesion = await login(world, world.entorno.adminUsuario, world.entorno.adminPassword);
    world.tokenAdmin = sesion.token;
  }
  const departamento = await crearDepartamento(world, world.tokenAdmin);
  const empleado = datosEmpleado(departamento.id);
  const alta = await pedir(world, 'POST', '/empleados', { body: empleado, token: world.tokenAdmin });
  if (alta.status !== 201) {
    throw new Error(
      `No se pudo crear el empleado ${empleado.id}: ${alta.status} ${JSON.stringify(alta.cuerpo)}`,
    );
  }
  const tokenActivacion = await esperarTokenActivacion(world, empleado.id, world.tokenAdmin);
  const reset = await pedir(world, 'POST', '/auth/reset-password', {
    auth: false,
    body: { token: tokenActivacion, contrasena: world.entorno.userPassword },
  });
  if (reset.status !== 200) {
    throw new Error(`reset-password falló: ${reset.status} ${JSON.stringify(reset.cuerpo)}`);
  }
  const sesion = await login(world, empleado.email, world.entorno.userPassword);
  return {
    empleado,
    departamento,
    token: sesion.token,
    role: sesion.role,
    contrasena: world.entorno.userPassword,
    tokenActivacion,
  };
}
