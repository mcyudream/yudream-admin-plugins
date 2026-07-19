export type LowresImage = ImageBitmap | HTMLImageElement

/** Decodes map PNGs efficiently where possible, while retaining support for browsers without ImageBitmap. */
export async function decodeLowresImage(blob: Blob): Promise<LowresImage> {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(blob)
  }
  const url = URL.createObjectURL(blob)
  try {
    const image = new Image()
    image.decoding = 'async'
    image.src = url
    await image.decode()
    return image
  }
  finally {
    URL.revokeObjectURL(url)
  }
}

export function releaseLowresImage(image: LowresImage | null | undefined): void {
  if (image && 'close' in image && typeof image.close === 'function') {
    image.close()
  }
}
