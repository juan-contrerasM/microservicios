import { setWorldConstructor, World } from '@cucumber/cucumber';
import { leerEntorno } from './entorno.js';

/**
 * Una instancia nueva por escenario. Cucumber no reutiliza el World,
 * así que el token y la última respuesta no se filtran al siguiente.
 */
export class Contexto extends World {
  constructor(options) {
    super(options);
    this.entorno = leerEntorno();
    this.baseUrl = this.entorno.baseUrl;
    this.token = null;
    this.tokenAdmin = null;
    this.tokenUsuario = null;
    this.tokenAlterado = null;
    this.tokenActivacion = null;
    this.tokenRecuperacion = null;
    this.ultimaRespuesta = null;
    this.empleado = null;
    this.departamento = null;
    this.otroEmpleado = null;
    this.empleadoRegistrado = null;
    this.vacacion = null;
    this.periodoVacaciones = null;
  }
}

setWorldConstructor(Contexto);
