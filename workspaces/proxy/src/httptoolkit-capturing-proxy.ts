import {EventEmitter} from 'events';
import * as path from 'path';
import * as os from 'os';
import * as url from 'url';
import * as qs from 'querystring';
import * as mockttp from 'mockttp';
import * as fs from 'fs-extra';
import launcher from '@httptoolkit/browser-launcher';
import {CallbackResponseResult} from 'mockttp/dist/rules/handlers';
import {CompletedRequest} from 'mockttp';
import {IApiInteraction} from './types';


export interface IWithSamples {
  getSamples(): IApiInteraction[]
}

export interface IHttpToolkitCapturingProxyConfig {
  proxyPort: number,
  flags: {
    chrome: boolean
  }
}

export interface IRequestFilter {
  shouldSkip(request: CompletedRequest): boolean
}

export class HttpToolkitCapturingProxy implements IWithSamples {
  private proxy!: mockttp.Mockttp;
  private chrome!: launcher.BrowserInstance;
  private requests: Map<string, mockttp.CompletedRequest> = new Map();
  private samples: IApiInteraction[] = [];
  private config!: IHttpToolkitCapturingProxyConfig;
  public readonly events: EventEmitter = new EventEmitter();

  async start(config: IHttpToolkitCapturingProxyConfig) {
    this.config = config;
    const tempBasePath = path.join(os.tmpdir(), 'optic-');
    const configPath = await fs.mkdtemp(tempBasePath);
    const certificateInfo = await mockttp.generateCACertificate({
      bits: 2048,
      commonName: 'Optic Labs Corp'
    });
    const certificatePath = path.join(configPath, '.optic', 'certificates');
    await fs.ensureDir(certificatePath);
    const certPath = path.join(certificatePath, 'ca.cert');
    const keyPath = path.join(certificatePath, 'ca.key');
    await fs.writeFile(certPath, certificateInfo.cert);
    await fs.writeFile(keyPath, certificateInfo.key);
    const https = {
      certPath,
      keyPath
    };

    const proxy = mockttp.getLocal({
      cors: true,
      debug: false,
      https,
      recordTraffic: false
    });

    this.proxy = proxy;

    proxy.addRules(
      {
        matchers: [
          new mockttp.matchers.HostMatcher('amiusing.httptoolkit.tech')
        ],
        handler: new mockttp.handlers.CallbackHandler(request => {
          const response: CallbackResponseResult = {
            statusCode: 302,
            headers: {
              location: `https://docs.useoptic.com`
            }
          };
          return response;
        })
      },
      {
        matchers: [
          new mockttp.matchers.WildcardMatcher()
        ],
        handler: new mockttp.handlers.PassThroughHandler()
      }
    );
    const requestFilter: IRequestFilter = {
      shouldSkip(req: mockttp.CompletedRequest) {
        return false;
      }
    };

    await proxy.on('request', (req: mockttp.CompletedRequest) => {
      if (!requestFilter.shouldSkip(req)) {
        this.requests.set(req.id, req);
      }
    });

    await proxy.on('response', (res: mockttp.CompletedResponse) => {
      if (this.requests.has(res.id)) {
        const req = this.requests.get(res.id) as mockttp.CompletedRequest;
        const queryString: string = url.parse(req.url).query || '';
        const queryParameters = qs.parse(queryString);

        const sample: IApiInteraction = {
          request: {
            method: req.method,
            host: req.hostname || '',
            url: req.path,
            headers: req.headers,
            cookies: {},
            queryParameters,
            body: extractBody(req)
          },
          response: {
            statusCode: res.statusCode,
            headers: res.headers,
            body: extractBody(res)
          }
        };
        this.events.emit('sample', sample)
        this.requests.delete(res.id)
      }
    });

    await proxy.start(config.proxyPort);

    if (config.flags.chrome) {
      this.chrome = await new Promise((resolve, reject) => {
        //@ts-ignore
        launcher((err, launch) => {
          if (err) {
            return reject(err);
          }
          const launchUrl = `https://docs.useoptic.com`;
          const spkiFingerprint = mockttp.generateSPKIFingerprint(certificateInfo.cert);
          const launchOptions: launcher.LaunchOptions = {
            profile: configPath,
            browser: 'chrome',
            proxy: `https://127.0.0.1:${config.proxyPort}`,
            noProxy: [
              '<-loopback>',
            ],
            options: [
              `--ignore-certificate-errors-spki-list=${spkiFingerprint}`
            ]
          };
          launch(launchUrl, launchOptions, function (err: any, instance: launcher.BrowserInstance | PromiseLike<launcher.BrowserInstance> | undefined) {
            if (err) {
              return reject(err);
            }
            resolve(instance);
          });
        });
      });
    }
  }

  async stop() {
    await this.proxy.stop();
    if (this.config.flags.chrome) {
      const promise = new Promise((resolve) => {
        const timeoutId = setTimeout(resolve, 2000);
        //@ts-ignore
        this.chrome.on('stop', () => {
          clearTimeout(timeoutId);
          resolve();
        });
      });
      this.chrome.stop();
      await promise;
    }
  }

  getSamples() {
    return this.samples;
  }
}

export function extractBody(req: mockttp.CompletedRequest | mockttp.CompletedResponse) {
  if (req.headers['content-type'] || req.headers['transfer-encoding']) {
    return req.body.json || req.body.formData || req.body.text;
  }
}