export function leerEntorno(env = process.env) {
  const baseUrl = (env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
  const intentos = Number(env.POLL_INTENTOS || 15);
  const intervaloMs = Number(env.POLL_INTERVALO_MS || 2000);
  if (!Number.isInteger(intentos) || intentos < 1) {
    throw new Error('POLL_INTENTOS debe ser un entero mayor o igual que 1');
  }
  if (!Number.isInteger(intervaloMs) || intervaloMs < 0) {
    throw new Error('POLL_INTERVALO_MS debe ser un entero mayor o igual que 0');
  }
  return {
    baseUrl,
    adminUsuario: env.ADMIN_USUARIO || '',
    adminPassword: env.ADMIN_PASSWORD || '',
    userPassword: env.USER_PASSWORD || '',
    pollIntentos: intentos,
    pollIntervaloMs: intervaloMs,
  };
}

export function exigirCredenciales(entorno) {
  if (!entorno.adminUsuario || !entorno.adminPassword || !entorno.userPassword) {
    throw new Error(
      'Faltan ADMIN_USUARIO, ADMIN_PASSWORD o USER_PASSWORD. Copia e2e-tests/.env.example a e2e-tests/.env.',
    );
  }
}
