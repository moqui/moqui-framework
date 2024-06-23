package ars.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import ars.rockycube.stomp.listener.ReceiptListener;

public class Receipt {
	private String receiptId;
	
	private ReceiptListener receiptListener;

	public String getReceiptId() {
		return receiptId;
	}

	public void setReceiptId(String receiptId) {
		this.receiptId = receiptId;
	}

	public ReceiptListener getReceiptListener() {
		return receiptListener;
	}

	public void setReceiptListener(ReceiptListener receiptListener) {
		this.receiptListener = receiptListener;
	}
	
}
