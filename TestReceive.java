public class TestReceive {
	static final int PORT = 32456;
	public static void main(String[] args)
	{
		try
		{
			RReceiveUDP receiver = new RReceiveUDP();
			// receiver.setMode(receiver.STOP_AND_WAIT);
			receiver.setMode(receiver.SLIDING_WINDOW);
			receiver.setModeParameter(1024);
			receiver.setFilename("less_important.txt");
			receiver.setLocalPort(32456);
			receiver.receiveFile();
		}
		catch(Exception e){ e.printStackTrace(); }
	}
}
