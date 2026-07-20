/** PRBM is BlueMap's binary terrain format; other renderers never need a decode worker. */
export function shouldCreatePrbmDecoder(renderer: string | undefined): boolean {
  return renderer === 'BLUEMAP'
}
