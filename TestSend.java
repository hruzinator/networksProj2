import java.net.InetSocketAddress;

public class TestSend {

	public static void main(String[] args)
	{
		try {
			RSendUDP sender = new RSendUDP();
			sender.setMode(sender.SLIDING_WINDOW);
			sender.setModeParameter(512);
			sender.setTimeout(1000);
			sender.setFilename("./important.txt");
			sender.setLocalPort(23456);
			sender.setReceiver(new InetSocketAddress("localhost", 32456));
			sender.sendFile();
		}
		catch(Exception e){ e.printStackTrace(); }
	}

}
