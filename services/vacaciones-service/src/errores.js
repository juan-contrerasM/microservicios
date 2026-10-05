export class ErrorApi extends Error {
	constructor(status, body) {
		super(body.mensaje);
		this.status = status;
		this.body = body;
	}
}
