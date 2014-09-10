/**
 * Copyright (c) 2014 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package cafe.image.reader;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;
import java.util.zip.InflaterInputStream;

import cafe.image.png.ChunkType;
import cafe.image.png.ColorType;
import cafe.image.png.Filter;
import cafe.image.png.PNGDescriptor;
import cafe.io.IOUtils;
import cafe.util.ArrayUtils;

/** 
 * Decodes and shows PNG images.
 * <p> 
 * Supports up to 8 bit depth sequential and Adam7 interlaced color and grayscale images.
 * Alpha channel and single color transparency are also supported where permitted by the
 * image color type.
 * 
 * @author Wen Yu, yuwen_66@yahoo.com 
 * @version 1.1 03/02/2012
 */
public class PNGReader extends ImageReader
{  
     /* PNG signature constant */
     public static final long SIGNATURE = 0x89504E470D0A1A0AL;
     
	 /* Define interlace method constants */
	 private static final byte NON_INTERLACED = 0;//sequential
	 private static final byte ADAM7 = 1;
	 
	 private static final int[] BLACK_WHITE_PALETTE = {0xFF000000, 0xFFFFFFFF};
	 private static final int[] FOUR_COLOR_PALETTE = {0xFF000000, 0xFF404040, 0xFF808080, 0xFFFFFFFF};
	 private static final int[] SIXTEEN_COLOR_PALETTE = {0xFF000000, 0xFF111111, 0xFF222222, 0xFF333333,
		 											   0xFF444444, 0xFF555555, 0xFF666666, 0xFF777777,
		 											   0xFF888888, 0xFF999999, 0xFFAAAAAA, 0xFFBBBBBB,
		 											   0xFFCCCCCC, 0xFFDDDDDD, 0xFFEEEEEE, 0xFFFFFFFF};	 
	 private static final int[] EIGHT_BIT_COLOR_PALETTE = new int[256];
	 
	 static {
		 for(int i = 0; i < 256; i++)
			 EIGHT_BIT_COLOR_PALETTE[i] = 0xFF000000|(i<<16)|(i<<8)|(i&0xff);
	 }

	 private static void apply_defilter(InputStream bis, byte[] pixBytes, int height, int bytesPerPixel, int bytesPerScanLine) throws Exception
	 {		 
		 int filter_type = Filter.NONE;

		 for (int j = 0, offset = 0; j < height; j++, offset += bytesPerScanLine)
		 {
              filter_type = bis.read();
              IOUtils.readFully(bis, pixBytes, offset, bytesPerScanLine);
              // Do the filter
              switch (filter_type) {
		  		case Filter.NONE:
		  			break;
		  		case Filter.SUB:
		  			Filter.defilter_sub(bytesPerPixel, bytesPerScanLine, pixBytes, offset);
		  			break;
		  		case Filter.UP:
		  			Filter.defilter_up(bytesPerScanLine, pixBytes, offset);
		  			break;
		  		case Filter.AVERAGE:
		  			Filter.defilter_average(bytesPerPixel, bytesPerScanLine, pixBytes, offset);
		  			break;
		  		case Filter.PAETH:
		  			Filter.defilter_paeth(bytesPerPixel, bytesPerScanLine, pixBytes, offset);
		  			break;
		  		default:
		  			break;
              }                         
		 }
	 }
	 
	 /* Define header variables */
	 private byte color_type;
	 private byte compression;
	 private byte filter_method; 

	 private byte interlace_method;
	 private float gamma = 0.45455f; // Default Gamma
     private boolean hasGamma;

	 private float displayExponent = 2.2f;
	 private byte[] alpha;
	 private byte[] gammaTable;
	 
	 private short[] gammaUShortTable;
	 
	 /* Define interlace related variables */
	 private int block_width;
	 private int block_height;
	 private int x_start;
	 private int y_start;
	 private int x_inc;
	 private int y_inc;
	 
	 /*
	  * Gamma correction is much more complicated when several color management chunks are present.
	  * The following two variables are use to partially fix the incorrect gamma correction applied
	  * in these situations.
	  */
	 private byte renderingIntent = -1; // Comes from sRGB chunk	 
	 private boolean hasICCP = false; // Comes from iCCP chunk
	
	 private void adjust_grayscale_PLTE(int[] palette) {
		 System.out.println("Transparent grayscale image!");
		 palette[alpha[1]&0xff] = (palette[alpha[1]&0xff]&0x00FFFFFF);	
	 }

	 private void adjust_PLTE()
	 {
		 System.out.println("Transparent indexed color image!");
		 int len = Math.min(alpha.length, rgbColorPalette.length);
		 for(int i = 0; i < len; i++)
           rgbColorPalette[i] = ((alpha[i]&0xff)<<24|(rgbColorPalette[i]&0x00FFFFFF));
	 }
	 
	 // Calculate variable values for different interlaced PNG passes
	 private boolean calculatePassVariables(int pass) {
		 switch(pass)
		 {
			 case 1:
				 block_width = (width/8)+((width%8)==0?0:1);
			     block_height = (height/8)+((height%8)==0?0:1);				     
			   	 x_start = y_start = 0;
				 x_inc = y_inc = 8;
				 break;
			 case 2:
				 if(width<5) return false;//skip pass
				 block_width = (width/8)+((width%8)<5?0:1);
			     block_height = (height/8)+((height%8)==0?0:1);					
			   	 x_start = 4;
				 y_start = 0;
			     x_inc = y_inc = 8;
				 break;
			 case 3:
				 if(height<5) return false;//skip pass
				 block_width = (width/4)+((width%4)==0?0:1);
			     block_height = (height/8)+((height%8)<5?0:1);					
			     x_start = 0;
				 y_start = 4;
			     x_inc = 4;
				 y_inc = 8;
				 break;
			 case 4:
				 if(width<3) return false;//skip pass
				 block_width = (width/4)+((width%4)<3?0:1);
			     block_height = (height/4)+((height%4)==0?0:1);					
			     x_start = 2;
				 y_start = 0;
			     x_inc = 4;
				 y_inc = 4;
				 break;
			 case 5: 
				 if(height<3) return false;//skip pass
				 block_width = (width/2)+((width%2)==0?0:1);
			     block_height = (height/4)+((height%4)<3?0:1);					
			     x_start = 0;
				 y_start = 2;
			     x_inc = 2;
				 y_inc = 4;
			     break;
			 case 6:
				 if(width<2) return false;//skip pass
				 block_width = (width/2);
			     block_height = (height/2)+((height%2)==0?0:1);					
			     x_start = 1;
				 y_start = 0;
			     x_inc = y_inc = 2;
				 break;
			 case 7:
				 if(height<2) return false;//skip pass
				 block_width = width;
			     block_height = (height/2);					
			     x_start = 0;
			     y_start = 1;
				 x_inc = 1;
				 y_inc = 2;
				 break;
			 default:
				 return false;
		 }
		 
		 return true;
	 }
	 
	 // Gamma correct for byte type image data
	 private void correctGamma(byte[] image, int width, int height)
	 {
		 int p_index = 0;
		 for (int i = 0; i < height; i++)
			 for (int j = 0; j < width; j++, p_index += 2)
				 image[p_index] = gammaTable[image[p_index]&0xff];
	 }
	 
	 // Gamma correction for palette based image data
	 private void correctGamma(int[] rgbColorPalette) {
		 for(int i = 0; i < rgbColorPalette.length; i++) {
			 byte red = gammaTable[((rgbColorPalette[i]&0xff0000)>>16)];
			 byte green = gammaTable[((rgbColorPalette[i]&0x00ff00)>>8)];
			 byte blue = gammaTable[(rgbColorPalette[i]&0x0000ff)];
			 rgbColorPalette[i] = ((rgbColorPalette[i]&0xff000000)|((red&0xff)<<16)|((green&0xff)<<8)|(blue&0xff));
		 }
	 }

	 // Gamma correction for int type image data
	 private void correctGamma(int[] image, int width, int height)
	 {
		 int p_index = 0;
		 for (int i = 0; i < height; i++)
		 {
			 for (int j = 0; j < width; j++, p_index++)
			 {
				 byte red = gammaTable[((image[p_index]&0xff0000)>>16)];
				 byte green = gammaTable[((image[p_index]&0x00ff00)>>8)];
				 byte blue = gammaTable[(image[p_index]&0x0000ff)];
				 image[p_index] = ((image[p_index]&0xff000000)|((red&0xff)<<16)|((green&0xff)<<8)|(blue&0xff));
			 }
		 }
	 }

	 // Gamma correction for short type image data
	 private void correctGamma(short[] image, int width, int height, int rgbStride, int alphaStride)
	 {
		 int p_index = 0;
		
		 for (int i = 0; i < height; i++)
		 {
			 for (int j = 0; j < width; j++, p_index += alphaStride)
			 {
				 for(int k = 0; k < rgbStride; k++, p_index++)
					 image[p_index] = gammaUShortTable[image[p_index]&0xffff];
			 }
		 }
	 }

	 // Byte type image data gamma correction table
	 private void createGammaTable(float gamma, float displayExponent)
     {
		 int size =  1 << 8;
		 gammaTable = new byte[size];
		 double decodingExponent = 1d / ((double)gamma * (double)displayExponent);
		 for (int i = 0; i < size; i++)
			 gammaTable[i] = (byte)(Math.pow((double)i / (size - 1), decodingExponent) * (size - 1));
     }
	 
	 // Short type image data gamma correction table
	 private void createUShortGammaTable(float gamma, float displayExponent)
     {
		 int size =  1 << 16;
		 gammaUShortTable = new short[size];
		 double decodingExponent = 1d / ((double)gamma * (double)displayExponent);
		 for (int i = 0; i < size; i++)
			 gammaUShortTable[i] = (short)(Math.pow((double)i / (size - 1), decodingExponent) * (size - 1));
     }
	 
	 private byte[] deflateRGBPixels(byte[] compr_data, boolean fullAlpha) throws Exception {
		 int bytesPerPixel = 0;
		 byte[] pixBytes;
			 
		 switch (bitsPerPixel)
		 {
		    case 8:
				if (fullAlpha)
			    	bytesPerPixel = 4;
				else 
					bytesPerPixel = 3;
				break;
			case 16:
				if (fullAlpha)
			    	bytesPerPixel = 8;
				else 
					bytesPerPixel = 6;
		    	break;
			default: 
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for RGB image...");
		 }
		 
		 bytesPerScanLine = width*bytesPerPixel;		 
		 
		 // Now inflate the data.
		 pixBytes = new byte[height * bytesPerScanLine];
         // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
        
		 apply_defilter(bis, pixBytes, height, bytesPerPixel, bytesPerScanLine);
		 
		 return pixBytes;
	 }
 	 
 	 private short[] generate16BitGrayscaleInterlacedPixels(byte[] compr_data) throws Exception {
 		 int bytesPerPixel = 0;
		 byte[] pix_interlaced;
		 int p_index = 0;
		 bytesPerPixel = 2;
		 
		 short grayMask = 0;
		 short[] spixels = null;
		 
		 if(alpha != null) {
			 grayMask = (short)((alpha[1]&0xff)|(alpha[0]&0xff)<<8);
			 spixels = new short[width*height*2];
		 } else
			 spixels = new short[width*height];
		  
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {
			 if(!calculatePassVariables(pass)) continue;
			 
			 bytesPerScanLine = bytesPerPixel*block_width;
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];

			 apply_defilter(bis, pix_interlaced, block_height, bytesPerPixel, bytesPerScanLine);
			 
			 p_index = x_start + width*y_start;
			 
			 if(alpha != null) { // Deal with single color transparency
				 for(int j = 0, s_index = 0, k = 0; j < block_height; j++) {
					 for (int i = 0; i < block_width; i++, p_index += x_inc) {
						 s_index = p_index<<1;
						 spixels[s_index] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff));
						 if(spixels[s_index] == grayMask) {
							 spixels[++s_index] = (short)0x0000;							   
						 } else {
							 spixels[++s_index] = (short)0xffff;
						 }
					 }
					 p_index = ((j+1)*y_inc+y_start)*width + x_start;
				 }				
			 } else {
				 for(int j = 0, k = 0; j < block_height; j++) {
					 for (int i = 0; i < block_width; i++, p_index += x_inc) {
						 spixels[p_index] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff));
					 }
					 p_index = ((j+1)*y_inc+y_start)*width + x_start;
				 }		
			 }
		 }
		 
		 bis.close();
		 
		 return spixels;	
	 }
 	 
 	 private short[] generate16BitGrayscalePixels(byte[] compr_data) throws Exception {
 		 //
 		 int bytesPerPixel = 1;
		 byte[] pixBytes;
			       
		 bytesPerPixel = 2;
		 bytesPerScanLine = bytesPerPixel*width;
		 // Now inflate the data.
		 pixBytes = new byte[height * bytesPerScanLine];
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));

		 apply_defilter(bis, pixBytes, height, bytesPerPixel, bytesPerScanLine);
		 
		 bis.close();
		 
		 if(alpha != null) {
			 short[] spixels = new short[width*height*2];
			 short grayMask = (short)((alpha[1]&0xff)|(alpha[0]&0xff)<<8);
			 
			 for(int i = 0, index = 0; i < pixBytes.length; index++) {
				 spixels[index] = (short)((pixBytes[i++]&0xff)<<8|(pixBytes[i++]&0xff));
				 if(spixels[index] == grayMask) {
					 spixels[++index] = (short)0x0000;							   
				 } else {
					 spixels[++index] = (short)0xffff;
				 }
			 }
			 
			 return spixels;
		 }
		 
		 return ArrayUtils.byteArrayToShortArray(pixBytes, true);
	 }
 	 
 	 private short[] generate16BitRGBInterlacedPixels(byte[] compr_data, boolean fullAlpha) throws Exception
	 {
 		 int bytesPerPixel = 0;
		 int p_index = 0;		 
		 byte[] pix_interlaced;
		 
		 short redMask = 0; 
		 short greenMask = 0;
		 short blueMask = 0;
		 
		 if(alpha != null) {
			 redMask = (short)((alpha[1]&0xff)|(alpha[0]&0xff)<<8);
			 greenMask = (short)((alpha[3]&0xff)|(alpha[2]&0xff)<<8);
			 blueMask = (short)((alpha[5]&0xff)|(alpha[4]&0xff)<<8);
		 }
		 
		 if (fullAlpha)
			 bytesPerPixel = 8;
		 else 
			 bytesPerPixel = 6;
	
		 short[] spixels = null;
		 
		 if(fullAlpha || alpha != null)
			 spixels = new short[width*height*4];
		 else
			 spixels = new short[width*height*3];
		 ////////////////////////////////////////////////////////////////////////////////////////////////
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
	  	 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {
			 if(!calculatePassVariables(pass)) continue;
			 
			 bytesPerScanLine = bytesPerPixel*block_width;
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];

			 apply_defilter(bis, pix_interlaced, block_height, bytesPerPixel, bytesPerScanLine);				
			
			 p_index = x_start + width*y_start;
			 
			 if (fullAlpha) {			
				for(int j = 0, s_index = 0, k = 0; j < block_height; j++) {
					for (int i = 0; i < block_width; i++, p_index += x_inc) {
						s_index = p_index<<2;
						spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Red
						spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Green
						spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Blue
						spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Alpha
					}
					p_index = ((j+1)*y_inc+y_start)*width + x_start;
				}				
			 } else if(alpha != null) { // Deal with single color transparency			
				for(int j = 0, s_index = 0, k = 0; j < block_height; j++) {
					for (int i = 0; i < block_width; i++, p_index += x_inc) {
						s_index = p_index<<2;
						spixels[s_index] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Red
						spixels[s_index+1] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Green
						spixels[s_index+2] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Blue
						if(spixels[s_index] == redMask && spixels[s_index + 1] == greenMask && spixels[s_index + 2] == blueMask) {
							spixels[s_index + 3] = 0x00;							   
						} else {
							spixels[s_index + 3] = (short)0xffff;
						}
					}
					p_index = ((j+1)*y_inc+y_start)*width + x_start;
				}				
			 } else {
				 for(int j = 0, s_index = 0, k = 0; j < block_height; j++) {
					 for (int i = 0; i < block_width; i++, p_index += x_inc) {
						 s_index = p_index*3;
						 spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Red
						 spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Green
						 spixels[s_index++] = (short)((pix_interlaced[k++]&0xff)<<8|(pix_interlaced[k++]&0xff)); // Blue
					 }
					 p_index = ((j+1)*y_inc+y_start)*width + x_start;
				 }		
			 }
		 }
		 
		 bis.close();
		 
		 return spixels;
	 }
 	 
 	 private short[] generate16BitRGBPixels(byte[] compr_data, boolean fullAlpha) throws Exception {
		 //
		 int bytesPerPixel = 0;
		 byte[] pixBytes;
				 
		 if (fullAlpha)
			 bytesPerPixel = 8;
		 else 
			 bytesPerPixel = 6;
		 	 
		 bytesPerScanLine = width*bytesPerPixel;		 

		 // Now inflate the data.
		 pixBytes = new byte[height * bytesPerScanLine];
		 
         // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
        
		 apply_defilter(bis, pixBytes, height, bytesPerPixel, bytesPerScanLine);
		 
		 short[] spixels = null;
		 
		 if(alpha != null) { // Deal with single color transparency
			 spixels = new short[width*height*4];
			 short redMask = (short)((alpha[1]&0xff)|(alpha[0]&0xff)<<8);
			 short greenMask = (short)((alpha[3]&0xff)|(alpha[2]&0xff)<<8);;
			 short blueMask = (short)((alpha[5]&0xff)|(alpha[4]&0xff)<<8);
		
			 for(int i = 0, index = 0; i < pixBytes.length; index += 4) {
				 short red = (short)((pixBytes[i++]&0xff)<<8|(pixBytes[i++]&0xff));
				 short green = (short)((pixBytes[i++]&0xff)<<8|(pixBytes[i++]&0xff));
				 short blue = (short)((pixBytes[i++]&0xff)<<8|(pixBytes[i++]&0xff));
				 spixels[index] = red;
				 spixels[index + 1] = green;
				 spixels[index + 2] = blue;
				 if(spixels[index] == redMask && spixels[index + 1] == greenMask && spixels[index + 2] == blueMask) {
					 spixels[index + 3] = (short)0x0000;							   
				 } else {
					 spixels[index + 3] = (short)0xffff;
				 }
			 }
		 } else
			 spixels = ArrayUtils.byteArrayToShortArray(pixBytes, true);
		 
		 return spixels;		 
	 }
 	
	 private int[] generate8BitRGBInterlacedPixels(byte[] compr_data, boolean fullAlpha) throws Exception
	 {
 		 int bytesPerPixel = 0;
		 int p_index = 0;
		 byte[] pix_interlaced;
		 int[] ipixels = new int[width*height];
				
		 if (fullAlpha)
			 bytesPerPixel = 4;
		 else 
			 bytesPerPixel = 3;
	
		 ////////////////////////////////////////////////////////////////////////////////////////////////
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
	  	 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {
			 if(!calculatePassVariables(pass)) continue;
			 
			 bytesPerScanLine = bytesPerPixel*block_width;
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];

			 apply_defilter(bis, pix_interlaced, block_height, bytesPerPixel, bytesPerScanLine);				
			
			 p_index = x_start + width*y_start;
			 
			 if (fullAlpha) {
				for(int j = 0, k = 0; j < block_height; j++) {
					for (int i = 0; i < block_width; i++, p_index += x_inc) {
						ipixels[p_index] = (((pix_interlaced[k++]&0xff)<<16)|((pix_interlaced[k++]&0xff)<<8)|(pix_interlaced[k++]&0xff)|((pix_interlaced[k++]&0xff)<<24));
					}
					p_index = ((j+1)*y_inc+y_start)*width + x_start;
				}
			 } else if(alpha != null) {
				for(int j = 0, k = 0; j < block_height; j++) {
					for (int i = 0; i < block_width; i++, p_index += x_inc){
						if((pix_interlaced[k]==alpha[1])&&(pix_interlaced[k+1]==alpha[3])&&(pix_interlaced[k+2]==alpha[5]))
							ipixels[p_index] = ((0x00<<24)|((pix_interlaced[k++]&0xff)<<16)|((pix_interlaced[k++]&0xff)<<8)|(pix_interlaced[k++]&0xff));
						else
							ipixels[p_index] = ((0xff<<24)|((pix_interlaced[k++]&0xff)<<16)|((pix_interlaced[k++]&0xff)<<8)|(pix_interlaced[k++]&0xff));
					}
					p_index = ((j+1)*y_inc+y_start)*width + x_start;
				}
			 } else {
				for(int j = 0, k = 0; j < block_height; j++) {
					for (int i = 0; i < block_width; i++, p_index += x_inc) {
						ipixels[p_index] = ((0xff<<24)|((pix_interlaced[k++]&0xff)<<16)|((pix_interlaced[k++]&0xff)<<8)|(pix_interlaced[k++]&0xff));
				 	}
			 		p_index = ((j+1)*y_inc+y_start)*width + x_start;
	 			}
			 }		 
		 }
		 
		 return ipixels;
	 }

	 private int[] generate8BitRGBPixels(byte[] compr_data, boolean fullAlpha) throws Exception
	 {
 		 int[] ipixels = new int[width*height];
			 
		 byte[] pixBytes = deflateRGBPixels(compr_data, fullAlpha);
		 
		 int image_size = width * height;
		 
		 if (fullAlpha) {			 
		     for (int i = 0, j = 0; i < image_size; i++) {
 		          ipixels[i] = (((pixBytes[j++]&0xff)<<16)|((pixBytes[j++]&0xff)<<8)|(pixBytes[j++]&0xff)|((pixBytes[j++]&0xff)<<24));
		     }		     
		 } else if(alpha != null) {
			 int _alpha = 0xff000000;
			 
			 for (int i = 0, j = 0; i < image_size; i++) {
				 
                 if((pixBytes[j]==alpha[1])&&(pixBytes[j+1]==alpha[3])&&(pixBytes[j+2]==alpha[5])) {
				     _alpha = 0x00000000;	
                 }
                 
                 ipixels[i] = (_alpha|((pixBytes[j++]&0xff)<<16)|((pixBytes[j++]&0xff)<<8)|(pixBytes[j++]&0xff));
			     _alpha = 0xff000000;
			 }			 
		 } else {			 
			 for (int i = 0, j = 0; i < image_size; i++) {
                 ipixels[i] = ((0xff<<24)|((pixBytes[j++]&0xff)<<16)|((pixBytes[j++]&0xff)<<8)|(pixBytes[j++]&0xff));
			 }			 
		 }
		 
		 return ipixels;
	 }
	 
	 private void generateGrayscaleInterlacedPixels(byte[] pixels, byte[] pix_interlaced, int block_width, int block_height, int padding, int p_index, int x_start, int y_start, int x_inc, int y_inc) {
		 //
		 int i = 0, safeEnd = block_width - padding;		 
			
		 switch (bitsPerPixel)
		 {		 	
		 	case 8:
		 		for(int j = 0, k = 0; j < block_height; j++) {
		 			for (int l = 0; l < block_width; l++, p_index += x_inc) {
		 				pixels[p_index] = pix_interlaced[k++];
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 	case 4:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=2)
			 		{
		 				pixels[p_index] = (byte)((pix_interlaced[i]>>>4)&0x0f);
		 				p_index += x_inc;
		 				pixels[p_index] = (byte)((pix_interlaced[i++]>>>0)&0x0f);
		 				p_index += x_inc;
			 		}
		 			if(padding != 0) {
		 				pixels[p_index] = (byte)((pix_interlaced[i++]>>>4)&0x0f);
		 				p_index += x_inc;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 	case 2:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=4, i++)
			 		{
		 				for(int l = 6; l >= 0; l-=2) {
		 					pixels[p_index] = (byte)((pix_interlaced[i]>>>l)&0x03);
			 				p_index += x_inc;
		 				}		 			
		 			}
		 			if(padding != 0) {
			 			for(int m = 0, n = 6; m < padding; m++, n-=2) {
			 				pixels[p_index] =  (byte)((pix_interlaced[i]>>>n)&0x03);
		 					p_index += x_inc;
			 			}
			 			i++;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 	case 1:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=8, i++) {
		 				for(int l = 7; l >= 0; l--) {
		 					pixels[p_index] = (byte)((pix_interlaced[i]>>>l)&0x01);
			 				p_index += x_inc;
		 				}		 			
		 			}
		 			if(padding != 0) {
			 			for(int m = 7; m >= (8-padding); m--) {
			 				pixels[p_index] = (byte)((pix_interlaced[i]>>>m)&0x01);
		 					p_index += x_inc;
			 			}
			 			i++;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 }
	 }

	 private void generateIndexedInterlacedPixels(byte[] pixels, byte[] pix_interlaced, int block_width, int block_height, int padding, int p_index, int x_start, int y_start, int x_inc, int y_inc) {
		 //
		 int i = 0, safeEnd = block_width - padding;
		 switch (bitsPerPixel)
		 {
		 	case 8:
		 		for (int j = 0, k = 0; j < block_height; j++)
		 		{				 
		 			for (int l = 0; l < block_width; l++, k++, p_index += x_inc) {
		 				pixels[p_index] =  pix_interlaced[k];
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}		 		
		 		break;
		 	case 4:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=2)
			 		{
			 			pixels[p_index] = (byte)(pix_interlaced[i]>>>4);
			 			p_index += x_inc;
			 			pixels[p_index] = pix_interlaced[i++];
			 			p_index += x_inc;
			 		}
		 			if(padding != 0) {
		 				pixels[p_index] = (byte)(pix_interlaced[i++]>>>4);
		 				p_index += x_inc;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 	case 2:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=4, i++)
			 		{	
		 				for(int l = 6; l >= 0; l-=2) {
		 					pixels[p_index] = (byte)((pix_interlaced[i]>>>l)&0x03);
			 				p_index += x_inc;
		 				}		 				
			 		}
		 			if(padding != 0) {
			 			for(int m = 0, n = 6; m < padding; m++, n-=2) {
			 				pixels[p_index] =  (byte)((pix_interlaced[i]>>>n)&0x03);
		 					p_index += x_inc;
			 			}
			 			i++;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 	case 1:
		 		for(int j = 0; j < block_height; j++) {
		 			for (int k = 0; k< safeEnd; k+=8, i++) {
		 				for(int l = 7; l >= 0; l--) {
		 					pixels[p_index] = (byte)((pix_interlaced[i]>>>l)&0x01);
			 				p_index += x_inc;
		 				}		 				
		 			}
		 			if(padding != 0) {
			 			for(int m = 7; m >= (8-padding); m--) {
			 				pixels[p_index] = (byte)((pix_interlaced[i]>>>m)&0x01);
		 					p_index += x_inc;
			 			}
			 			i++;
		 			}
		 			p_index = ((j+1)*y_inc+y_start)*width + x_start;
		 		}
		 		break;
		 }
	 }

	 // Calculate bytes per scan line for index color image
	 private int getBytesPerScanLine(int block_width) {
		 //
		 int padding = 0;
		 
		 switch (bitsPerPixel)
		 {
		 	case 8:
				bytesPerScanLine = block_width;
			    break;
			case 4:
				padding = block_width%2;
				bytesPerScanLine = (block_width>>>1);
				break;
			case 2:
				padding = block_width%4;
				bytesPerScanLine = (block_width>>>2);
				break;
			case 1:
				padding = block_width%8;
				bytesPerScanLine = (block_width>>>3);
				break;
			default:
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for PNG image...");					
		 }
		 
		 if(padding != 0) bytesPerScanLine += 1;
		 
		 return bytesPerScanLine;
	 }
	 
	 private int getPadding(int block_width) {		 
		 //
		 int padding = 0;
		 
		 switch (bitsPerPixel)
		 {
		    case 8:				
			    break;
			case 4:
				padding = block_width%2;
				break;
			case 2:
				padding = block_width%4;
				break;
			case 1:
				padding = block_width%8;
				break;
		 }
		 
		 return padding;
	 }

	 private byte[] process_grayscaleAlphaImage(byte[] compr_data) throws Exception {
 		 //
 		 int bytesPerPixel = 0;
		 byte[] pixBytes;
		 
		 switch (bitsPerPixel)
		 {
		    case 8:
				bytesPerPixel = 2;			
			    break;
		    case 16:
		    	bytesPerPixel = 4;		  
		    	break;
			default:
				System.out.println("... " + bitsPerPixel + " bit color depth is invalid for full alpha grayscale image...");
		 }
		 
		 bytesPerScanLine = width*bytesPerPixel;
		 
		 // Now inflate the data.  		 
		 pixBytes = new byte[height * bytesPerScanLine];
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
       
		 apply_defilter(bis, pixBytes, height, bytesPerPixel, bytesPerScanLine);	
		 
		 return pixBytes;
 	 }
	 
	 private byte[] process_grayscaleAlphaInterlacedImage(byte[] compr_data) throws Exception {
 		 //
 		 int bytesPerPixel = 0;
		 int p_index = 0;
		 byte[] pix_interlaced;
		 byte[] pixels;
				
		 switch (bitsPerPixel)
		 {
		    case 8:
				bytesPerPixel = 2;
			    break;
			case 16:
		    	bytesPerPixel = 4;
		    	break;
			default: 
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for grayscale full alpha image...");
		 }
		 
		 pixels = new byte[width*height*bytesPerPixel];
		 
		 ////////////////////////////////////////////////////////////////////////////////////////////////
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
	  	 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {				
			 if(!calculatePassVariables(pass)) continue;
			 
			 bytesPerScanLine = bytesPerPixel*block_width;
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];

			 apply_defilter(bis, pix_interlaced, block_height, bytesPerPixel, bytesPerScanLine);				
			
			 p_index = x_start + width*y_start;			 
			 
			 if(bitsPerPixel == 8) {
				 for(int j = 0, k = 0; j < block_height; j++) {
					 for (int i = 0, b_index = 0; i < block_width; i++, p_index += x_inc) {
						 b_index = p_index << 1;
						 pixels[b_index++] = pix_interlaced[k++];
						 pixels[b_index++] = pix_interlaced[k++];
					 }
					 p_index = ((j+1)*y_inc+y_start)*width + x_start;
				 }
			 } else if(bitsPerPixel == 16) {
				 for(int j = 0, b_index = 0, k = 0; j < block_height; j++) {
					 for (int l = 0; l < block_width; l++, p_index += x_inc) {
						 b_index = p_index << 2;
						 pixels[b_index++] = pix_interlaced[k++];
						 pixels[b_index++] = pix_interlaced[k++];
						 pixels[b_index++] = pix_interlaced[k++];
						 pixels[b_index++] = pix_interlaced[k++];
					 }
					 p_index = ((j+1)*y_inc+y_start)*width + x_start;		 			
				 }
			 }
		 }
		 
		 return pixels;
 	 }
	 
	 private byte[] process_grayscaleImage(byte[] compr_data) throws Exception {
		 //
 		 int bytesPerPixel = 1;
		 int padding = 0;
		 byte[] pixBytes;
			       
		 switch (bitsPerPixel)
		 {
		    case 8:				
				rgbColorPalette = EIGHT_BIT_COLOR_PALETTE;
				bytesPerScanLine = width;
			    break;
			case 4:
				padding = width%2;
				rgbColorPalette = SIXTEEN_COLOR_PALETTE;
				bytesPerScanLine = (width>>>1) + ((padding == 0)?0:1);
				break;
			case 2:
				padding = width%4;
				rgbColorPalette = FOUR_COLOR_PALETTE;
				bytesPerScanLine = (width>>>2) + ((padding == 0)?0:1);
				break;
			case 1:
				padding = width%8;
				rgbColorPalette = BLACK_WHITE_PALETTE;
				bytesPerScanLine = (width>>>3) + ((padding == 0)?0:1);
				break;
			default:
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for grayscale image...");
		 }
		 // Now inflate the data.        
		 pixBytes = new byte[height * bytesPerScanLine];
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));

		 apply_defilter(bis, pixBytes, height, bytesPerPixel, bytesPerScanLine);
		 
		 bis.close();
		 
		 return pixBytes;
	 }
	 
	 private byte[] process_grayscaleInterlacedImage(byte[] compr_data) throws Exception {
 		 int bytesPerPixel = 0;
		 byte[] pix_interlaced;
		 byte[] pixels;
		 int padding = 0;
     
		 switch (bitsPerPixel)
		 {
		    case 8:
				bytesPerPixel = 1;
				rgbColorPalette = EIGHT_BIT_COLOR_PALETTE;
			    break;
			case 1:
				bytesPerPixel = 1;
				rgbColorPalette = BLACK_WHITE_PALETTE;
				break;
			case 2:
				bytesPerPixel = 1;
				rgbColorPalette = FOUR_COLOR_PALETTE;
				break;
			case 4:
				bytesPerPixel = 1;
				rgbColorPalette = SIXTEEN_COLOR_PALETTE;
			    break;			
			default: 
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for grayscale image...");
		 }
		 
		 pixels = new byte[width*height*bytesPerPixel];
		 
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {
			 if(!calculatePassVariables(pass)) continue;
			 
			 bytesPerScanLine = getBytesPerScanLine(block_width);
			 padding = getPadding(block_width);
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];

			 apply_defilter(bis, pix_interlaced, block_height, bytesPerPixel, bytesPerScanLine);
			 generateGrayscaleInterlacedPixels(pixels, pix_interlaced, block_width, block_height, padding, x_start+width*y_start, x_start, y_start, x_inc, y_inc);
		 }
		 
		 bis.close();
		 
		 // Pack and return the pixels
		 return ArrayUtils.packByteArray(pixels, width, 0, bitsPerPixel, pixels.length);	
	 }
	 
	 // Image data processing and BufferedImage generating module
	 private BufferedImage process_IDAT(byte[] compr_data) throws Exception
	 {	
		 byte[] bpixels = null;
		 int[] ipixels = null;
		 short[] spixels = null;
		 WritableRaster raster = null;
		 DataBuffer db = null;
		 ColorModel cm = null;
		 
		 switch (ColorType.fromInt(color_type))
		 {
		   case GRAY_SCALE:
			   //Create a BufferedImage			   			  
			   if(bitsPerPixel == 16) {
				   if(interlace_method==NON_INTERLACED) {
					   spixels = generate16BitGrayscalePixels(compr_data);			   
				   } else if(interlace_method==ADAM7) {
					   spixels = generate16BitGrayscaleInterlacedPixels(compr_data);
				   }				   
				   if(hasGamma && renderingIntent == -1 && !hasICCP) {
					   if(alpha != null)
						   correctGamma(spixels, width, height, 1, 1);
					   else
						   correctGamma(spixels, width, height, 1, 0);
				   }
				   if(alpha != null) { // Deal with single color transparency
					   db = new DataBufferUShort(spixels, spixels.length);
					   int[] off = {0, 1};//band offset, we have only one band start at 2
					   raster = Raster.createInterleavedRaster(db, width, height, width*2, 2, off, null);
					   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
				   } else {
					   db = new DataBufferUShort(spixels, spixels.length);
					   int[] off = {0};//band offset, we have only one band start at 0
					   raster = Raster.createInterleavedRaster(db, width, height, width, 1, off, null);
					   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
				   }
			   } else {
				   if(interlace_method==NON_INTERLACED) {
					   bpixels = process_grayscaleImage(compr_data);			   
				   } else if(interlace_method==ADAM7) {
					   bpixels = process_grayscaleInterlacedImage(compr_data);
				   }
				   db = new DataBufferByte(bpixels, bpixels.length);
				   if(bitsPerPixel == 8) {			   
					   int[] off = {0};//band offset, we have only one band start at 0
					   raster = Raster.createInterleavedRaster(db, width, height, width, 1, off, null);			
				   } else {
					   raster = Raster.createPackedRaster(db, width, height, bitsPerPixel, null);
				   }
				   if(hasGamma && renderingIntent == -1 && !hasICCP)
					   correctGamma(rgbColorPalette);
				   cm = new IndexColorModel(bitsPerPixel, rgbColorPalette.length, rgbColorPalette, 0, true, -1, DataBuffer.TYPE_BYTE);
			   }
			   return new BufferedImage(cm, raster, false, null);
		   case GRAY_SCALE_WITH_ALPHA:
			   if(interlace_method==NON_INTERLACED)
				   bpixels = process_grayscaleAlphaImage(compr_data);
			   else if(interlace_method==ADAM7)
				   bpixels = process_grayscaleAlphaInterlacedImage(compr_data);
			   // Create a BufferedImage. WARNING: this create a custom type BufferedImage
			   if(bitsPerPixel == 16) {
				   spixels = ArrayUtils.byteArrayToShortArray(bpixels, true);
				   if(hasGamma && renderingIntent == -1 && !hasICCP) correctGamma(spixels, width, height, 1, 1);
				   db = new DataBufferUShort(spixels, spixels.length);
				   int[] off = {0, 1};//band offset, we have only one band start at 0
				   raster = Raster.createInterleavedRaster(db, width, height, width*2, 2, off, null);
				   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);				   	
			   } else {
				   if(hasGamma && renderingIntent == -1 && !hasICCP) correctGamma(bpixels, width, height);
				   db = new DataBufferByte(bpixels, bpixels.length);				
				   int[] off = {0, 0, 0, 1};//band offset, we have 4 bands
				   raster = Raster.createInterleavedRaster(db, width, height, bytesPerScanLine, 2, off, null);
				   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
			   }
			   return new BufferedImage(cm, raster, false, null);		
		   case TRUE_COLOR:
			   if(bitsPerPixel == 16) {
				   if(interlace_method==NON_INTERLACED)
					   spixels = generate16BitRGBPixels(compr_data, false);
				   else {
					   spixels = generate16BitRGBInterlacedPixels(compr_data, false);
				   }					   
				   if(hasGamma && renderingIntent == -1 && !hasICCP) {
					   if(alpha != null)
						   correctGamma(spixels, width, height, 3, 1);
					   else
						   correctGamma(spixels, width, height, 3, 0);
				   }
				   if(alpha != null) { // Deal with single color transparency
					   db = new DataBufferUShort(spixels, spixels.length);
					   int[] off = {0, 1, 2, 3}; //band offset, we have 4 bands
					   int numOfBands = 4;
					   int trans = Transparency.TRANSLUCENT;
					   int[] nBits = {16, 16, 16, 16};						
					   raster = Raster.createInterleavedRaster(db, width, height, width*numOfBands, numOfBands, off, null);
					   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), nBits, true, false,
				                trans, DataBuffer.TYPE_USHORT);
				   } else {
					   db = new DataBufferUShort(spixels, spixels.length);
					   int[] off = {0, 1, 2}; //band offset, we have 3 bands
					   int numOfBands = 3;
					   int trans = Transparency.OPAQUE;
					   int[] nBits = {16, 16, 16};						
					   raster = Raster.createInterleavedRaster(db, width, height, width*numOfBands, numOfBands, off, null);
					   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), nBits, false, false,
				                trans, DataBuffer.TYPE_USHORT);
				   }
			   } else {
				   if(interlace_method==NON_INTERLACED)
					   ipixels = generate8BitRGBPixels(compr_data, false);
			       else if(interlace_method==ADAM7)
			    	   ipixels = generate8BitRGBInterlacedPixels(compr_data, false);
				   if(hasGamma && renderingIntent == -1 && !hasICCP)
						 correctGamma(ipixels, width, height);
				   //Create a BufferedImage
				   db = new DataBufferInt(ipixels, ipixels.length);
				   // We have to use 4 samples to account for single pixel transparency
				   raster = Raster.createPackedRaster(db, width, height, width,  new int[] {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000}, null);
				   cm = new DirectColorModel(32, 0x00FF0000, 0x0000ff00, 0x000000ff, 0xff000000);
			   }
			   return new BufferedImage(cm, raster, false, null);
		   case TRUE_COLOR_WITH_ALPHA:
			   if(bitsPerPixel == 16) {
				   if(interlace_method==NON_INTERLACED)
					   spixels = generate16BitRGBPixels(compr_data, true);
				   else {
					   spixels = generate16BitRGBInterlacedPixels(compr_data, true);
				   }
				   if(hasGamma && renderingIntent == -1 && !hasICCP) correctGamma(spixels, width, height, 3, 1);
				   db = new DataBufferUShort(spixels, spixels.length);
				   int[] off = {0, 1, 2, 3}; //band offset, we have 4 bands
				   int numOfBands = 4;
				   int trans = Transparency.TRANSLUCENT;
				   int[] nBits = {16, 16, 16, 16};						
				   raster = Raster.createInterleavedRaster(db, width, height, width*numOfBands, numOfBands, off, null);
				   cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), nBits, true, false,
			                trans, DataBuffer.TYPE_USHORT);
			   } else {
				   if(interlace_method==NON_INTERLACED)
					   ipixels = generate8BitRGBPixels(compr_data, true);
			       else if(interlace_method==ADAM7)
			    	   ipixels = generate8BitRGBInterlacedPixels(compr_data, true);
				   if(hasGamma && renderingIntent == -1 && !hasICCP)
						 correctGamma(ipixels, width, height);
				   //Create a BufferedImage
				   db = new DataBufferInt(ipixels, ipixels.length);
				   raster = Raster.createPackedRaster(db, width, height, width,  new int[] {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000}, null);
				   cm = new DirectColorModel(32, 0x00FF0000, 0x0000ff00, 0x000000ff, 0xff000000);
			   }
			   return new BufferedImage(cm, raster, false, null);
		   case INDEX_COLOR:
			   bpixels = null;
			   if(interlace_method==NON_INTERLACED)
				   bpixels = process_IndexedImage(compr_data);
		       else if(interlace_method==ADAM7)
		    	   bpixels = process_IndexedInterlacedImage(compr_data);
			   // Create BufferedImage
			   db = new DataBufferByte(bpixels, bpixels.length);
			   if(bitsPerPixel != 8) {
				   raster = Raster.createPackedRaster(db, width, height, bitsPerPixel, null);
			   } else {
				   int[] off = {0};//band offset, we have only one band start at 0
				   raster = Raster.createInterleavedRaster(db, width, height, width, 1, off, null);
			   }
			   if(hasGamma && renderingIntent == -1 && !hasICCP)
					 correctGamma(rgbColorPalette);
			   cm = new IndexColorModel(bitsPerPixel, rgbColorPalette.length, rgbColorPalette, 0, true, -1, DataBuffer.TYPE_BYTE);
			   
			   return new BufferedImage(cm, raster, false, null);			
		   default:
			   System.out.println("..Invalid color type...");
		 }

		 return null;
	 }
	 
	 private byte[] process_IndexedImage(byte[] compr_data) throws Exception
	 {
		 bytesPerScanLine = getBytesPerScanLine(width);
		 // Now inflate the data.        
		 byte[] pixBytes = new byte[height * bytesPerScanLine];
         // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));

		 apply_defilter(bis, pixBytes, height, 1, bytesPerScanLine);
		 
		 return pixBytes;
  	 }
	 
	 private byte[] process_IndexedInterlacedImage(byte[] compr_data) throws Exception
	 {
		 int padding = 0;
		 byte[] pix_interlaced;
		 byte[] pixels = new byte[width*height];
      
		 switch (bitsPerPixel)
		 {
			case 1:
			case 2:
			case 4:
		    case 8:		    	
				break;
			default: 
				System.out.println("... " + bitsPerPixel + " bit color depth is not valid for indexed image...");
		 }
		 // Wrap an InflaterInputStream with a bufferedInputStream to speed up reading
		 BufferedInputStream bis = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compr_data)));
		 // Decoding the image pass by pass. There are 7 passes for ADAM7 interlacing method. 
		 for (int pass = 1; pass < 8; pass++)
		 {
			 if(!calculatePassVariables(pass)) continue;
			 bytesPerScanLine = getBytesPerScanLine(block_width);
			 padding = getPadding(block_width);			 
			 // Now inflate the data.
			 pix_interlaced = new byte[block_height * bytesPerScanLine];
			 apply_defilter(bis, pix_interlaced, block_height, 1, bytesPerScanLine);			 
			 generateIndexedInterlacedPixels(pixels, pix_interlaced, block_width, block_height, padding, x_start + width*y_start, x_start, y_start, x_inc, y_inc);			
		 }
		
		 bis.close();
		 
		 // Pack and return the pixels
		 return ArrayUtils.packByteArray(pixels, width, 0, bitsPerPixel, pixels.length);	
	 }		
	 
	 public BufferedImage read(InputStream is) throws Exception
     {
		 // Local variables for reading chunks
		 int data_len = 0;
         int chunk_type = 0;
		  
         /** ByteArrayOutputStream to write compressed image data */
         ByteArrayOutputStream compr_data = new ByteArrayOutputStream(65536);
	
         /** Read the 8 bytes signature */
         /** 
          * The first eight bytes of a PNG file always contain the following (decimal) values:
          * 137 80 78 71 13 10 26 10 ======  0x89504e470d0a1a0aL
          * Decimal Value ASCII Interpretation 
          * 137 --- A byte with its most significant bit set (``8-bit character'') 
          * 80  --- P 
          * 78  --- N 
          * 71  --- G 
          * 13  --- Carriage-return (CR) character, a.k.a. CTRL-M or ^M 
          * 10  --- Line-feed (LF) character, a.k.a. CTRL-J or ^J 
          * 26  --- CTRL-Z or ^Z 
          * 10  --- Line-feed (LF) character, a.k.a. CTRL-J or ^J 
          *******************************************************************
          */
		  //long signature = ((IOUtils.readIntMM(is)&0xffffffffffffL)<<32)|IOUtils.readIntMM(is);
          long signature = IOUtils.readLongMM(is);
		 
          if (signature != SIGNATURE)
          {
		      System.out.println("--- NOT A PNG IMAGE ---");
		      return null;
		  }
  		  
		  /**
		  byte[] signature = new byte[8];
		  is.read(signature, 0, 8);

		  if (!(new String(signature, 1, 3, "US-ASCII").equals("PNG")))//Is this a PNG?
		  {
			System.out.println("--- NOT A PNG IMAGE ---");
			return;
		  }
		  else System.out.println("--- PNG IS NOT GIF ---");
          */
		  
		  //*******************************
		  // Chunks follow, start with IHDR
		  //*******************************
          /** 
           * Chunk layout
           * Each chunk consists of four parts:
           * 
           * Length
           *     A 4-byte unsigned integer giving the number of bytes in the chunk's data field.
		   *	 The length counts only the data field, not itself, the chunk type code, or the CRC.
		   *	 Zero is a valid length. Although encoders and decoders should treat the length as unsigned, 
		   *	 its value must not exceed 2^31-1 bytes.

           * Chunk Attribute
           *     A 4-byte chunk type code. For convenience in description and in examining PNG files, 
		   *	 type codes are restricted to consist of upper-case and lower-case ASCII letters 
		   *	 (A-Z and a-z, or 65-90 and 97-122 decimal). However, encoders and decoders must treat 
		   *	 the codes as fixed binary values, not character strings. For example, it would not be
		   *	 correct to represent the type code IDAT by the EBCDIC equivalents of those letters. 
		   *	 Additional naming conventions for chunk types are discussed in the next section.

           * Chunk Data
           *     The data bytes appropriate to the chunk type, if any. This field can be of zero length.

           * CRC
           *     A 4-byte CRC (Cyclic Redundancy Check) calculated on the preceding bytes in the chunk,
		   *	 including the chunk type code and chunk data fields, but not including the length field. 
		   *	 The CRC is always present, even for chunks containing no data. See CRC algorithm. 
	       */

          /** Read header */
		  if (!read_IHDR(is))
			    throw new IOException("NOT A VALID PNG IMAGE");

		  // Dumping
		  System.out.println("--- PNG IMAGE INFO ---");
		  System.out.println("image width: "+width);
		  System.out.println("image height: "+height);
		  System.out.println("image bit depth: "+bitsPerPixel);
		  System.out.println(ColorType.fromInt(color_type));
	  	  System.out.println("image compression: "+ compression + " - " + PNGDescriptor.getCompressionTypeDescrition(compression));
		  System.out.println("image filter method: " + filter_method + " - " + PNGDescriptor.getFilterTypeDescription(filter_method));
		  System.out.println("image interlace method: " + interlace_method + " - " + PNGDescriptor.getInterlaceTypeDescription(interlace_method));
		  System.out.println("--- END PNG IMAGE INFO ---");
		  // End of dumping

		  while (true)
		  {
			  data_len = IOUtils.readIntMM(is);
			  chunk_type = IOUtils.readIntMM(is);
			  //System.out.println("chunk type: 0x"+Integer.toHexString(chunk_type));

			  if (chunk_type == ChunkType.IEND.getValue())
				  break;
			
			  ChunkType chunk = ChunkType.fromInt(chunk_type);
			
			  switch (chunk)
			  {
			  	case IDAT: 
			  	{
			  		read_IDAT(is, data_len, compr_data);
			  		break;
			  	}
			  	case TRNS:
			  	{
			  		alpha = new byte[data_len];
			  		is.read(alpha, 0, data_len);
			  		IOUtils.readUnsignedIntMM(is);// CRC
			  		if(color_type == 3)
			  			adjust_PLTE();
			  		else if(color_type == 0) {
			  			if(bitsPerPixel == 1)
			  				adjust_grayscale_PLTE(BLACK_WHITE_PALETTE);
			  			else if(bitsPerPixel == 2)
			  				adjust_grayscale_PLTE(FOUR_COLOR_PALETTE);
			  			else if(bitsPerPixel == 4)
			  				adjust_grayscale_PLTE(SIXTEEN_COLOR_PALETTE);
			  			else if(bitsPerPixel == 8)
			  				adjust_grayscale_PLTE(EIGHT_BIT_COLOR_PALETTE);
			  		}						 
			  		else if(color_type == 2)
			  			System.out.println("full color transparent image!");
			  		break;
			  	}
			  	case GAMA:
			  	{
			  		read_GAMMA(is, data_len);
			  		break;
			  	}
			  	case SRGB:
			  	{
			  		read_SRGB(is, data_len);
			  		break;
			  	}
			  	case PLTE:
			  	{
			  		rgbColorPalette = new int[data_len/3];
			  		read_PLTE(is, data_len);
			  		break;
			  	}
			  	case ICCP:
			  		hasICCP = true;					   
			  	default:
			  	{
			  		is.skip(data_len);
			  		IOUtils.readUnsignedIntMM(is);// CRC
			  		break;
			  	}
			  }
		  }
	  
		  is.close();

		  return process_IDAT(compr_data.toByteArray());
     }
	 
	 private void read_GAMMA(InputStream is, int data_len) throws Exception
	 {
		 if(data_len != 4){
			 System.out.println("Invalid Gamma data length: " + data_len);
		     return;
		 }
		 hasGamma = true;
		 gamma = (IOUtils.readUnsignedIntMM(is)/100000.0f);
		 if(bitsPerPixel == 16) {
			 createUShortGammaTable(gamma, displayExponent);
		 } else
			 createGammaTable(gamma, displayExponent);
		 IOUtils.readUnsignedIntMM(is);// CRC
	 }
	 
	 private void read_IDAT(InputStream is, int data_len, ByteArrayOutputStream compr_data) throws Exception
	 {
 		 byte[] buf = new byte[data_len];
		 IOUtils.readFully(is,buf,0,data_len);
		 compr_data.write(buf, 0, data_len);
 		 IOUtils.readUnsignedIntMM(is);// CRC
	 }
	 
	 private boolean read_IHDR(InputStream is) throws Exception
	 {
		 /** 
		  * Header layout
		  * Width:              4 bytes
		  * Height:             4 bytes
		  * Bit depth:          1 byte
		  * Color type:         1 byte
		  * Compression method: 1 byte
		  * Filter method:      1 byte
		  * Interlace method:   1 byte
		  ****************************
		  * Width and height give the image dimensions in pixels. 
		  * They are 4-byte integers. Zero is an invalid value. 
		  * The maximum for each is 2^31-1 in order to accommodate 
		  * languages that have difficulty with unsigned 4-byte values. 
		  *************************************************************
		  *    Color    Allowed    Interpretation
		  *    Attribute    Bit Depths
		  *    0       1,2,4,8,16  Each pixel is a grayscale sample.
		  *    2       8,16        Each pixel is an R,G,B triple.
		  *    3       1,2,4,8     Each pixel is a palette index; a PLTE chunk must appear.
		  *    4       8,16        Each pixel is a grayscale sample, followed by an alpha sample.
		  *    6       8,16        Each pixel is an R,G,B triple, followed by an alpha sample.
		  ***************************************************************************************
		  */
		 /** We are expecting IHDR */
		 if ((IOUtils.readIntMM(is) != 13)||(IOUtils.readIntMM(is) != ChunkType.IHDR.getValue()))
			 return false;

		 byte[] hdr = new byte[13];

		 // Read header data
		 IOUtils.readFully(is,hdr,0,13);
                   
		 width = IOUtils.readIntMM(hdr, 0);
		 height = IOUtils.readIntMM(hdr, 4);

		 bitsPerPixel = hdr[8];
		 color_type = hdr[9];
		 compression = hdr[10];
		 filter_method = hdr[11];
		 interlace_method = hdr[12];

		 IOUtils.readUnsignedIntMM(is);// CRC

		 return true;
	 }
	 
	 private void read_PLTE(InputStream is, int data_len) throws Exception
	 {
   		 int table_indx = 0;
		 byte[] rgb_table = new byte[data_len];
		 int len = data_len/3;
		 IOUtils.readFully(is,rgb_table,0,data_len);
  	     for(int i=0;i<len;i++)
		 	 rgbColorPalette[i] = ((0xff<<24)|((rgb_table[table_indx++]&0xff)<<16)|((rgb_table[table_indx++]&0xff)<<8)|(rgb_table[table_indx++]&0xff));
		 IOUtils.readUnsignedIntMM(is);// CRC
	 }
	 
	 private void read_SRGB(InputStream is, int data_len) throws Exception
	 {
		 if(data_len!=1){
			 System.out.println("Invalid SRGB data length: " + data_len);
		     return;
		 }		 
		 renderingIntent = (byte)IOUtils.read(is);
		 IOUtils.readUnsignedIntMM(is);// CRC
	 }
}