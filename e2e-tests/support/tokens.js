export function extraerToken(mensaje) {
  if (typeof mensaje !== 'string') {
    return null;
  }
  const match = /[?&]token=([^&\s]+)/.exec(mensaje);
  return match ? decodeURIComponent(match[1]) : null;
}

export function alterarFirma(jwt) {
  const partes = String(jwt).split('.');
  if (partes.length !== 3 || !partes[2]) {
    throw new Error('El token no tiene el formato de un JWT');
  }
  const firma = partes[2];
  const reemplazo = firma.endsWith('a') ? 'b' : 'a';
  partes[2] = `${firma.slice(0, -1)}${reemplazo}`;
  return partes.join('.');
}
