import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

import edu.utulsa.unet.RSendUDPI;
import edu.utulsa.unet.UDPSocket;

public class RSendUDP implements RSendUDPI {
	public final int STOP_AND_WAIT = 0;
	public final int SLIDING_WINDOW = 1;

	private final int BUFFER_SIZE = 1500; //in bytes
	private final int HEADER_LENGTH = 12; //in bytes

	private int localPort = 12987;
	private int mode = SLIDING_WINDOW;
	private long modeParameter = 256; //for sliding window, this is the window size
	private String filename;
	private InetSocketAddress receiver;

	private long timeout = 1000; //timeout length in milliseconds. Default is one second.
	private long backSeqNum = 0;
	private long frontSeqNum = 0;
	private DatagramPacket[] messageBuffer;
	private long[] timeouts;

	private boolean gotFinAck = false;
	private boolean sendFinSyn = false;

	/**
	 * Gets the name of the file to be sent.
	 */
	@Override
	public String getFilename() {
		return filename;
	}

	/**
	 * Gets the local port /
	 */
	@Override
	public int getLocalPort() {
		return localPort;
	}

	/**
	 * Returns the reliable delivery mode for the sender.
	 * 
	 * @return 
	 */
	@Override
	public int getMode() {
		return mode;
	}

	@Override
	public long getModeParameter() {
		return modeParameter;
	}

	/**
	 * Returns the InetSocketAddress of the remote receiver.
	 * The InetSocketAddress contains the recevier's IP address
	 * (or fully qualified domain name) along with a remote port.
	 */
	@Override
	public InetSocketAddress getReceiver() {
		return receiver;
	}

	/**
	 * Returns the timeout value in milliseconds
	 */
	@Override
	public long getTimeout() {
		return timeout;
	}

	/**
	 * Sets the name to be given to a sent file.
	 */
	@Override
	public void setFilename(String fname) {
		filename = fname;
	}

	/**
	 * Set the local port number to be used by the host.
	 * 
	 * @return true if the port number is valid. False if it is not valid.
	 */
	@Override
	public boolean setLocalPort(int lp) {
		if(lp > 65535 || lp < 0){
			return false;
		}
		localPort=lp;
		return true;
	}

	/**
	 * Set the reliable delivery mode.
	 * 
	 * 0 = stop-and-wait
	 * 1 = sliding window
	 * 
	 * @return true if mode was set. False if it was not.
	 */
	@Override
	public boolean setMode(int m) {
		if(m==STOP_AND_WAIT){
			mode = m;
		}
		else if(m==SLIDING_WINDOW){
			mode = m;
		}
		else{
			return false;
		}
		return true;
	}

	/**
	 * Set the size of the sliding window in bytes. This method
	 * will have no effect when stop-and-wait is the current mode.
	 * 
	 * @return true if mode parameter was set. False if it was
	 * not set (either because of an error or because stop-and-wait
	 * is the current mode).
	 */
	@Override
	public boolean setModeParameter(long mp) {
		if(mode==SLIDING_WINDOW){
			modeParameter=mp;
			return true;
		}
		return false;
	}

	/**
	 * Specify the IP address (or fully qualified domain name) and
	 * port number of the remote receiver.
	 * 
	 * @return True if the operation completed successfully. False 
	 * otherwise.
	 */
	@Override
	public boolean setReceiver(InetSocketAddress r) {
		if(r.isUnresolved()){
			return false;
		}
		receiver = r;
		return true;
	}

	/**
	 * Set the timeout length in milliseconds.
	 * 
	 * @return true if the operation completed successfully. False 
	 * if it did not.
	 */
	@Override
	public boolean setTimeout(long t) {
		if(t > 0){
			timeout = t;
			return true;
		}
		return false;
	}

	private int getWindowSize(){
		if(frontSeqNum >= backSeqNum){ 
			return (int)(frontSeqNum-backSeqNum);
		}
		else { //front pointer looped around and the back pointer hasn't yet
			return (int)(frontSeqNum + (messageBuffer.length-backSeqNum));
		}
	}

	@Override
	public boolean sendFile() {
		if(mode == STOP_AND_WAIT){
			messageBuffer = new DatagramPacket[1];
		}
		else if(mode == SLIDING_WINDOW){
			messageBuffer = new DatagramPacket[(int) modeParameter];
		}
		else {
			System.out.println("Improperly set mode encountered. File cannot be sent.");
			return false; 
		}

		timeouts = new long[messageBuffer.length];

		try {
			FileReader f = new FileReader(filename);
			BufferedReader fileReader = new BufferedReader(f);

			UDPSocket s = new UDPSocket(localPort);
			s.setSoTimeout((int) (timeout/100)); //make it small in relation a normal packet's timeouts

			//tell the user what is going on
			System.out.println("Sending " + filename + " from " + s.getLocalAddress() + 
					":" + s.getLocalPort() + " to " + receiver.toString() + " with ..."); //TODO file length
			
			//send out the first few packets
			boolean sentFinSyn = false;
			while(!sentFinSyn && getWindowSize() < modeParameter){
				sentFinSyn = sendNextPacket(fileReader, s);
				frontSeqNum++;
			}
			
			boolean getFinAck = false;
			/*
			 * Send the packets one by one, while also listening for ACKs.
			 * This will loop until we have both recieved the FIN flag for an
			 * ACK AND our buffer size is 1
			*/
			while(getWindowSize()!=0 || !getFinAck){
				//listen for a new ACK
				byte[] ackBuffer = new byte[BUFFER_SIZE];
				try{
					s.receive(new DatagramPacket(ackBuffer, ackBuffer.length));

					//accept ack
					long ackSeqNum = ((ackBuffer[4] & 0xFF)<<24) + ((ackBuffer[5] & 0xFF)<<16) + ((ackBuffer[6] & 0xFF)<<8) + ((ackBuffer[7] & 0xFF));
					int synAck = (ackBuffer[1] & 2) >> 1; 
					if(synAck == 1 && ackSeqNum >= backSeqNum && ackSeqNum <= frontSeqNum){

						timeouts[(int) (ackSeqNum%timeouts.length)] = 0L; //reset the timer

						System.out.println("message " + ackSeqNum + " acknowledged.");
						if(ackSeqNum == backSeqNum){ //we can slide the window
							do{ //slide the window
								//check to see if this is the last datagram
								byte[] currentData = messageBuffer[(int) (ackSeqNum%messageBuffer.length)].getData();
								int finFlag = (currentData[1]>>2)&1;
								if(finFlag == 1){
									getFinAck = true;
								}

								messageBuffer[(int) (ackSeqNum%messageBuffer.length)] = null; //nullify the buffer entry
								
								//slide the window
								frontSeqNum++;
								backSeqNum++;

								sendNextPacket(fileReader, s);

							}while(timeouts[(int) (backSeqNum%timeouts.length)] != 0L);
							 // ^repeat until we get an indication that we have not recieved an ACK for the associated packet yet

						}
					}
				}
				catch(SocketTimeoutException e){
					/* okay, I know this is bad, but It's midnight and 
					 * I'm getting lazy. I'll take bad programmer points 
					 * for using a try-catch for program logic */
				}

				//make sure that we resend an OLD packet if we don't get an ACK in time
				for(long i=backSeqNum; i<frontSeqNum; i++){
					int index = (int) (i%timeouts.length);
					if(timeouts[index] <= System.nanoTime() && timeouts[index] != 0L){ //resend old packet
						s.send(messageBuffer[index]);
						timeouts[index] = System.nanoTime()+timeout;
					}
				}
			}
			//we can close the connection
			s.close();
			f.close();

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	/**
	 * 
	 * @param fileReader
	 * @param s
	 * @return True if we just sent the last packet. False if we can continue sending more packets
	 * @throws IOException
	 */
	private boolean sendNextPacket(BufferedReader fileReader, UDPSocket s) throws IOException{
		
		boolean sentAFin = false;
		
		//set up header for the next packet
		byte[] buffer = new byte[BUFFER_SIZE];
		buffer[0] = HEADER_LENGTH;
		buffer[1] = (byte) mode;
		//will be determining fin flag and data_length later
		buffer[4] = (byte)((frontSeqNum>>24)& 0xFF);
		buffer[5] = (byte)((frontSeqNum>>16)& 0xFF);
		buffer[6] = (byte)((frontSeqNum>>8)& 0xFF);
		buffer[7] = (byte)((frontSeqNum) & 0xFF);

		//construct the data segment
		int packetPtr = HEADER_LENGTH;
		for(; packetPtr<buffer.length-1; packetPtr++){
			int next;
			next = fileReader.read();

			if(next == -1){
				buffer[1] |= 1 << 2; //set fin flag to 1
				sentAFin = true;
				break;
			}
			buffer[packetPtr] = (byte)next;
		}

		//finish constructing the header
		int dataLength = packetPtr-HEADER_LENGTH;
		buffer[2] = (byte)(dataLength >> 8);
		buffer[3] = (byte)(dataLength & 0xFF);

		//send the packet
		DatagramPacket sendPacket = new DatagramPacket(buffer, packetPtr,
				receiver.getAddress(), receiver.getPort());
		s.send(sendPacket);
		
		//update buffers
		messageBuffer[(int) (frontSeqNum%messageBuffer.length)] = sendPacket;
		timeouts[(int) (frontSeqNum%timeouts.length)] = System.nanoTime() + timeout;
		System.out.println("Message " + frontSeqNum + 
				" sent with " + dataLength + " bytes of actual data");
		
		return sentAFin;
	}
}
