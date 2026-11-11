// Exporta el contrato que sirve la app (src/openapi.json) a openapi.yaml en la raíz del servicio.
//
//   npm run openapi:export          reescribe openapi.yaml
//   npm run openapi:export -- --check   sale con 1 si openapi.yaml quedó desactualizado
//
// src/openapi.json es la fuente (la imagen solo copia src/); openapi.yaml es la copia versionada
// que exige el estándar E08. tests/contrato.test.js falla si ambos difieren.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stringify } from 'yaml';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const origen = join(raiz, 'src', 'openapi.json');
const destino = join(raiz, 'openapi.yaml');

export function comoYaml() {
	const spec = JSON.parse(readFileSync(origen, 'utf8'));
	return stringify(spec, { lineWidth: 0, aliasDuplicateObjects: false });
}

const texto = comoYaml();
if (process.argv.includes('--check')) {
	const actual = existsSync(destino) ? readFileSync(destino, 'utf8').replace(/\r\n/g, '\n') : '';
	if (actual !== texto) {
		console.error('openapi.yaml desactualizado: ejecute npm run openapi:export');
		process.exit(1);
	}
} else {
	writeFileSync(destino, texto);
	console.log(`Escrito ${destino}`);
}
