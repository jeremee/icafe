package cafe.image.util;

import java.io.*;
import java.awt.*;
import java.awt.image.*;


public class NQCanvas extends Canvas {

    /**
	 * 
	 */
	private static final long serialVersionUID = -5075876371476649415L;
	private NeuQuant nq = null;
	private int pixels[] = null;
    private int w = 0;;
    private int h = 0;
    private Image image = null;
    private String url = null;
    
    public NQCanvas (String url) {
        this.url = url;
    }
    
    public void set () {
        try {
            System.err.println ("Fetching " + url + " ...");
            Image img = null;
            try {
                java.net.URL u = new java.net.URL (url);
                img = Toolkit.getDefaultToolkit ().getImage (u);
            } catch (Exception ee) {
                // filename
                img = Toolkit.getDefaultToolkit ().getImage (url);
            }
            java.awt.MediaTracker tracker = new java.awt.MediaTracker(this);
            tracker.addImage(img, 0);
            try {
		        tracker.waitForID(0);
	        } catch (InterruptedException e) { }
            System.err.println ("w = " + img.getWidth (this));
            System.err.println ("h = " + img.getHeight (this));
            set (img);
        } catch (Exception ex) {
            System.err.println(ex);
        }
    }
    
    public void set (Image img) throws IOException {
        nq = new NeuQuant (img, this);
        nq.init();
        w = img.getWidth (this);
    	h = img.getHeight (this);
        pixels = new int [w * h];
        java.awt.image.PixelGrabber pg
            = new java.awt.image.PixelGrabber(img, 0, 0, w, h, pixels, 0, w);
       	try {
	        pg.grabPixels();
	   	} catch (InterruptedException e) { }
	    if ((pg.getStatus() & java.awt.image.ImageObserver.ABORT) != 0) {
	        throw new IOException ("Image pixel grab aborted or errored");
	  	}
	  	for (int i = 0; i < w*h; i++) pixels[i] = nq.convert(pixels[i]);
	  	this.image = this.createImage(new MemoryImageSource(w, h, pixels, 0, w));
    }
    
    public void paint (Graphics graphics) {
        if (image == null) set ();
        if (image != null) graphics.drawImage (image, 0, 0, this);
    } 

    public static void main (String [] args) {
        NQCanvas canvas = new NQCanvas (args[0]);
        java.awt.Frame frame = new java.awt.Frame("NeuQuant Test");
	    frame.setSize (500, 500);
	    frame.add (canvas);
	    frame.setVisible(true);
	    frame.addWindowListener (new java.awt.event.WindowAdapter() {
	        public void windowClosing(java.awt.event.WindowEvent e) { 
		        System.err.println("Closing program");		
		        System.exit(0); }
	    } );

    }
}