import { mkdirSync } from 'node:fs';

mkdirSync(new URL('./reports/', import.meta.url), { recursive: true });

// El default export ES el perfil. Con "type": "module", envolverlo en
// `{ default: ... }` deja el perfil un nivel más adentro y Cucumber lo ignora.
export default {
  paths: ['features/**/*.feature'],
  import: ['support/**/*.js', 'step_definitions/**/*.js'],
  format: [
    'summary',
    'html:reports/cucumber.html',
    'json:reports/cucumber.json',
    'junit:reports/junit.xml',
  ],
  language: 'es',
};
