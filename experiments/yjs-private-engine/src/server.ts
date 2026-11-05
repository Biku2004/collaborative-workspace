import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { Worker } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';
import { InputError, LIMITS, parseRequest, parseRichRequest } from './protocol.js';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };

function respond(res: ServerResponse, status: number, body: unknown, maximum: number = LIMITS.outputBytes): void {
  if (res.destroyed || res.writableEnded) return;
  const output = JSON.stringify(body);
  if (Buffer.byteLength(output) > maximum) { respond(res, 422, { code: 'TOO_LARGE' }); return; }
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(output);
}

export function createEngineServer(deadlineMs: number = LIMITS.deadlineMs, workerUrl = new URL('./worker.js', import.meta.url)) {
  let active = 0;
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    if (req.method !== 'POST' || (req.url !== '/v1/reconstruct' && req.url !== '/v2/reconstruct')) { respond(res, 404, { code: 'NOT_FOUND' }); return; }
    const rich = req.url === '/v2/reconstruct';
    if (active >= LIMITS.activeWorkers) { req.resume(); respond(res, 503, { code: 'BUSY' }); return; }
    active++;
    let worker: Worker | undefined;
    let finished = false;
    let bodyBytes = 0;
    const chunks: Buffer[] = [];
    const finish = (status: number, body: unknown) => {
      if (finished) return;
      finished = true; clearTimeout(timer);
      if (!worker) active--;
      if (worker) void worker.terminate();
      respond(res, status, body, rich ? LIMITS.richOutputBytes : LIMITS.outputBytes);
    };
    const timer = setTimeout(() => finish(504, { code: 'TIMEOUT' }), deadlineMs);
    req.on('aborted', () => finish(400, { code: 'INVALID_REQUEST' }));
    req.on('error', () => finish(400, { code: 'INVALID_REQUEST' }));
    req.on('data', (chunk: Buffer) => {
      if (finished) return;
      bodyBytes += chunk.length;
      if (bodyBytes > (rich ? LIMITS.richBodyBytes : LIMITS.bodyBytes)) { finish(413, { code: 'TOO_LARGE' }); req.resume(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (finished) return;
      let updates: Uint8Array[];
      let commit = false;
      try {
        const request: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (rich) ({ updates, commit } = parseRichRequest(request));
        else updates = parseRequest(request);
      }
      catch (error) { finish(400, { code: error instanceof InputError ? error.code : 'INVALID_REQUEST' }); return; }
      try { worker = new Worker(workerUrl, { workerData: rich ? { format: contract.format, updates, commit } : updates, resourceLimits: { maxOldGenerationSizeMb: 64, maxYoungGenerationSizeMb: 16, stackSizeMb: 4 } }); }
      catch { finish(500, { code: 'ENGINE_FAILURE' }); return; }
      worker.once('message', (message: { ok: boolean; result?: unknown; code?: string }) => {
        if (message.ok) finish(200, message.result);
        else finish(message.code === 'ENGINE_FAILURE' ? 500 : 422, { code: message.code });
      });
      worker.once('error', () => finish(500, { code: 'ENGINE_FAILURE' }));
      worker.once('exit', () => { active--; if (!finished) finish(500, { code: 'ENGINE_FAILURE' }); });
    });
    res.on('close', () => { if (!finished) finish(400, { code: 'CLIENT_DISCONNECTED' }); });
  });
  server.requestTimeout = deadlineMs;
  server.headersTimeout = Math.max(deadlineMs, 1000);
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const raw = process.env.YJS_ENGINE_PORT ?? '8091';
  if (!/^\d+$/.test(raw) || Number(raw) < 1 || Number(raw) > 65535) throw new Error('Invalid YJS_ENGINE_PORT');
  createEngineServer().listen(Number(raw), '127.0.0.1', () => console.log(`Private prototype listening on 127.0.0.1:${raw}`));
}
