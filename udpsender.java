import java.net.DatagramPacket;
import edu.utulsa.unet.UDPSocket; 
//import java.net.DatagramSocket;
import java.net.InetAddress;

public class udpsender {
	static final String SERVER = "129.244.202.16";
	static final int PORT = 32456;

	public static void main(String[] args)
	{
		try {

			byte [] buffer = ("Hello World- or rather Mauricio saying hello through UDP").getBytes();
			UDPSocket socket = new UDPSocket(23456);
			//DatagramSocket socket = new DatagramSocket(23456);
			socket.send(new DatagramPacket(buffer, buffer.length,
 				InetAddress.getByName(SERVER), PORT));
		}
		catch(Exception e){ e.printStackTrace(); }
	}
}
