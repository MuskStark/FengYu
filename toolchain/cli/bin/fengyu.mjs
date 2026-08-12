#!/usr/bin/env node
import { main } from '../src/cli.mjs';
import { renderError } from '../src/errors.mjs';
main(process.argv.slice(2)).catch(error => { console.error(renderError(error)); process.exitCode = 1 });
