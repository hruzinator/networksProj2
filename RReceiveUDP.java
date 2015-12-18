import edu.utulsa.unet.RReceiveUDPI;
import edu.utulsa.unet.UDPSocket;

import java.io.FileOutputStream;
import java.net.DatagramPacket;

public class RReceiveUDP implements RReceiveUDPI {
	
	public final int STOP_AND_WAIT = 0;
	public final int SLIDING_WINDOW = 1;
	
	private final int BUFFER_SIZE = 1500;
	private final int HEADER_LENGTH = 6;
	
	private int localPort = 12987;
	private int mode = SLIDING_WINDOW;
	private int modeParameter = 256; //for sliding window, this is the window size
	private String filename;
	
	private int backSeqNum=0;
	private int frontSeqNum=0;
	private DatagramPacket[] messageBuffer;

	/**
	 * Gets the name of the file to be received.
	 */
	@Override
	public String getFilename() {
		return filename;
	}

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
	 * Sets the name to be given to a received file.
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
			frontSeqNum = 1;
			modeParameter = 1;
		}
		else if(m==SLIDING_WINDOW){
			mode = m;
			frontSeqNum = modeParameter;
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
		if(mp > Integer.MAX_VALUE){
			System.out.println("Mode parameters longer than the length of an int cause "
				+ " problems in the code. Cannot set the mode parameter this high.");
			return false;
		}
		if(mode==SLIDING_WINDOW){
			modeParameter=(int)mp;
			frontSeqNum=modeParameter-1;
			return true;
		}
		return false;
	}

	private int getWindowSize(){
		if(frontSeqNum >= backSeqNum){ 
			return frontSeqNum-backSeqNum;
		}
		else { //front pointer looped around and the back pointer hasn't yet
			return frontSeqNum + modeParameter-backSeqNum;
		}
	}
	
	@Override
	public boolean receiveFile() {
		if(mode==STOP_AND_WAIT){
			messageBuffer = new DatagramPacket[1];
		}
		else if(mode==SLIDING_WINDOW){
			messageBuffer = new DatagramPacket[modeParameter];
		}
		else {
			System.out.println("Improperly set mode encountered. File cannot be sent.");
			return false; 
		}
		
		try {
			byte[] buffer = new byte[BUFFER_SIZE]; //a buffer for recieving info from the socket
			UDPSocket s = new UDPSocket(localPort);
			System.out.println("Waiting for a connection on " + s.getLocalSocketAddress() + ":" + s.getLocalPort());
			System.out.println("Using ARQ algorithm: " + (mode == STOP_AND_WAIT ? 
							"Stop-and-wait": ("sliding window with window size " + modeParameter)));
			FileOutputStream netWriter = new FileOutputStream(filename);
			
			boolean getFin = false; //we are done when we receive the FIN flag and all the datagrams preceding the FIN flag
			while(!getFin){
				System.out.println("The window size is: " + getWindowSize());
				System.out.println("Buffer back: " + backSeqNum + ", buffer front: " + frontSeqNum);
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				s.receive(packet);
				
				//extract fields from the datagram
				int headerLength = (int)buffer[0];
				byte flags = buffer[1];
				int sMode = flags & 1;
				int synAck = (flags & 2) >> 1; //0 if SYN packet, 1 if ACK packet
				int finFlag = (flags & 4) >> 2;
				int dataLength = (((buffer[2]) & 0xFF)<<8) + (buffer[3] & 0xFF);
				int seqNumber = ((buffer[4] & 0xFF)<<8) + ((buffer[5] & 0xFF));
				System.out.println("fin: " + finFlag + ", syn/ack: " + synAck + ", sMode: " + sMode + ", headerLength: " + headerLength + ", dataLength: " + dataLength + ", seqNumber: " + seqNumber);
				int buffSeqNum = seqNumber%messageBuffer.length;
				if(synAck == 0 && ((frontSeqNum>backSeqNum) ? buffSeqNum >= backSeqNum && buffSeqNum <= frontSeqNum 
					: buffSeqNum >= frontSeqNum && buffSeqNum <= backSeqNum)){
					messageBuffer[buffSeqNum] = packet;
					if(buffSeqNum==backSeqNum){ //slide the window
						do{
							int finFlag1 = (messageBuffer[backSeqNum%messageBuffer.length].getData()[1] & 4) >> 2;
							
							if(finFlag1 == 1){
								getFin = true;
								System.out.println("GetFin was set");
								frontSeqNum = buffSeqNum;
							}
							
							netWriter.write(messageBuffer[backSeqNum%messageBuffer.length].getData(), headerLength, dataLength);
							messageBuffer[backSeqNum%messageBuffer.length] = null;
							
							byte[] replyBuffer = makeReplyBuffer(backSeqNum, getFin);
							s.send(new DatagramPacket(replyBuffer, replyBuffer.length, packet.getAddress(), packet.getPort()));
							
							backSeqNum=(backSeqNum+1)%messageBuffer.length;
							if(!getFin){
								frontSeqNum=(frontSeqNum+1)%messageBuffer.length;
							}
						}while(messageBuffer[backSeqNum%messageBuffer.length] != null);
					}
					System.out.println("Received message " + seqNumber + " from a sender at " + packet.getAddress() + ":" + packet.getPort());
				}
				else if(synAck==0 && seqNumber < backSeqNum){ //sender must have not recieved our ACK. Resend it
					byte[] replyBuffer = makeReplyBuffer(seqNumber, (finFlag==0 ? false : true));
					s.send(new DatagramPacket(replyBuffer, replyBuffer.length, packet.getAddress(), packet.getPort()));
				}
			}
			netWriter.close();
			//s.disconnect();
			//s.close();
			System.out.println("Finished receiving file");
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	private byte[] makeReplyBuffer(int seqNumber, boolean isLast) {
		byte[] buffer = new byte[HEADER_LENGTH];
		buffer[0] = HEADER_LENGTH;
		buffer[1] = (byte)(mode + 2);
		if(isLast)
			buffer[1] += 4;
		buffer[2] = 0;
		buffer[3] = 0;
		//System.out.println("sequence number to send: " + seqNumber);
		buffer[4] = (byte)(seqNumber >> 8);
		buffer[5] = (byte)(seqNumber%256);
		return buffer;
	}
}
