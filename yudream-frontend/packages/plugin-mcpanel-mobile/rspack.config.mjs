import path from 'node:path';
import * as Repack from '@callstack/repack';
import { rspack } from '@rspack/core';


const dirname = Repack.getDirname(import.meta.url);

// mcpanel 移动端 remote：exposes './module'，默认导出页面组件。
// shared 单例（react / react-native / @yudream/plugin-sdk-mobile）由宿主 App 注入。
export default (env) => {
  const { mode = 'production', platform = 'android' } = env ?? {};
  return {
    mode,
    context: dirname,
    entry: './src/entry.tsx',
    devtool: false,
    resolve: {
      ...Repack.getResolveOptions(platform),
    },
    output: {
      path: path.join(dirname, 'dist-mobile'),
      uniqueName: 'mcpanel',
      filename: '[name].js',
      // RN 无 document，publicPath 'auto' 会在 eval 时抛错中断 remoteEntry；
      // chunk 全部经宿主 ScriptManager 按文件名回源，这里必须显式留空。
      publicPath: '',
    },
    // 宿主缓存管线 v1 按单 remoteEntry 校验/翻转：关闭 chunk 拆分
    optimization: {
      splitChunks: { cacheGroups: { default: false, vendors: false } },
      maxAsyncRequests: 1,
      maxInitialRequests: 1,
      runtimeChunk: false,
    },
    module: {
      rules: [
        ...Repack.getJsTransformRules(),
        ...Repack.getAssetTransformRules(),
      ],
    },
    plugins: [
      // 独立 CLI 构建没有 react-native bundle 注入的 env，
      // platform/output 需在此显式提供（产物即单 remoteEntry.js）。
      new Repack.RepackPlugin({
        platform: 'android',
        output: {
          bundleFilename: 'remoteEntry.js',
          assetsPath: 'assets',
          auxiliaryAssetsPath: 'assets',
        },
      }),
      new Repack.plugins.ModuleFederationPluginV2({
        name: 'mcpanel',
        dts: false,
        filename: 'remoteEntry.js',
        exposes: {
          './module': './src/entry.tsx',
        },
        shared: {
          react: { singleton: true, eager: true, requiredVersion: '18.3.1' },
          'react-native': { singleton: true, eager: true },
          '@yudream/plugin-sdk-mobile': { singleton: true, eager: true, version: '0.1.0' },
        },
      }),
    ],
  };
};
