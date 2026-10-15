package com.holoprint.online;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

/** Kept separate so that a Java without image support can't break anything else. */
final class ThumbDecoder {
	private ThumbDecoder() {}

	static int[] decode(byte[] bytes, int W, int H) throws Exception {
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
		if (img == null) return null;
		int iw = img.getWidth(), ih = img.getHeight();
		if (iw <= 0 || ih <= 0) return null;
		// centre-crop to the thumbnail's shape
		double want = W / (double) H;
		int cw = iw, ch = ih;
		if (iw / (double) ih > want) cw = (int) (ih * want);
		else ch = (int) (iw / want);
		int ox = (iw - cw) / 2, oy = (ih - ch) / 2;
		int[] out = new int[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int x0 = ox + x * cw / W, x1 = Math.max(x0 + 1, ox + (x + 1) * cw / W);
				int y0 = oy + y * ch / H, y1 = Math.max(y0 + 1, oy + (y + 1) * ch / H);
				long r = 0, g = 0, b = 0, n = 0;
				int stepX = Math.max(1, (x1 - x0) / 4), stepY = Math.max(1, (y1 - y0) / 4);
				for (int yy = y0; yy < y1 && yy < ih; yy += stepY) {
					for (int xx = x0; xx < x1 && xx < iw; xx += stepX) {
						int c = img.getRGB(xx, yy);
						r += (c >> 16) & 0xFF;
						g += (c >> 8) & 0xFF;
						b += c & 0xFF;
						n++;
					}
				}
				if (n == 0) n = 1;
				out[y * W + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}
}
