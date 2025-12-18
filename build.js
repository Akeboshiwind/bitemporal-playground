import tailwindPlugin from 'bun-plugin-tailwind';
import { parseArgs } from 'util';
import { watch, mkdir } from 'fs/promises';

const { values, _positionals } = parseArgs({
    args: Bun.argv,
    options: {
        watch: { type: 'boolean' },
        'base-path': { type: 'string', default: '' },
    },
    strict: true,
    allowPositionals: true,
});

const basePath = values['base-path'] || '';


// >> Build

async function build({entrypoints, outdir, target, plugins}) {
    const build = await Bun.build({
        entrypoints,
        outdir,
        minify: !values.watch,
        sourcemap: 'external',
        target,
        plugins,
    });

    if (!build.success) {
        console.error('Build failed');
        for (const message of build.logs) {
            console.error(message);
        }
        process.exit(1);
    }
}

async function buildFrontend() {
    await mkdir('./target/public', { recursive: true });
    await build({
        entrypoints: ['./build/app/app.js'],
        outdir: './target/public',
        target: 'browser',
        plugins: [tailwindPlugin],
    });

    // Process index.html with base path substitution
    let html = await Bun.file('./src/index.html').text();
    html = html.replace(/href="\/app\.css"/g, `href="${basePath}/app.css"`);
    html = html.replace(/src="\/app\.js"/g, `src="${basePath}/app.js"`);
    await Bun.write('./target/public/index.html', html);
}



// >> Main

console.log('[bun] Building project...');
await buildFrontend();

if (values.watch) {
    console.log('[bun/watcher] Watching for changes...');
    const watcher = watch('./build', { recursive: true });

    for await (const {filename} of watcher) {
        console.log(`[bun/watcher] Change detected in ${filename}. Rebuilding...`);
        await buildFrontend();
    }
}
