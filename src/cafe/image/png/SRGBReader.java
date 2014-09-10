/**
 * Copyright (c) 2014 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package cafe.image.png;

import java.io.IOException;

import cafe.util.Reader;

/**
 * PNG sRGB chunk reader
 *  
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 10/16/2013
 */
public class SRGBReader implements Reader {

	private Chunk chunk;
	private byte renderingIntent;
	
	public SRGBReader(Chunk chunk) {
		//
		if (chunk.getChunkType() != ChunkType.SRGB) {
			throw new IllegalArgumentException("Not a valid sRGB chunk.");
		}
		
		this.chunk = chunk;
	}
	
	/**
	 * sRGB rendering intent:
	 * <p>
	 * 0 - Perceptual:
	 * for images preferring good adaptation to the output device gamut at the expense of
	 * colorimetric accuracy, such as photographs.
	 * <p>
	 * 1 - Relative colorimetric:
	 * for images requiring colour appearance matching (relative to the output device white point),
	 * such as logos.
	 * <p>
	 * 2 - Saturation:
	 * for images preferring preservation of saturation at the expense of hue and lightness,
	 * such as charts and graphs.
	 * <p>
	 * 3 - Absolute colorimetric:
	 * for images requiring preservation of absolute colorimetry, such as previews of images destined
	 * for a different output device (proofs).
	 */
	public byte getRenderingIntent() {
		return renderingIntent;
	}

	@Override
	public void read() throws IOException {
		byte[] data = chunk.getData();
		if(data.length > 0)
			renderingIntent = data[0]; 
		else renderingIntent = -1;
	}
}
