import socket
import threading
import time
import queue
import config
import constants
from logger import log  # Import logger

class UDPSocketManager:
    def __init__(self, receive_callback, local_port=0):
        """
        Initializes the UDP socket manager.
        :param receive_callback: Function to call when a packet is received.
                                 It should accept (data: bytes, address: tuple).
        :param local_port: The local port to bind to (0 for random available port).
        """
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(('', local_port))  # Bind to a specific or random port
        self.local_address = self.socket.getsockname()
        log.info(f"UDP Socket bound to {self.local_address}")  # Use logger

        self.server_address = (config.SERVER_ADDRESS, config.SERVER_PORT)
        self.receive_callback = receive_callback
        self.running = False
        self.receive_thread = None
        self.send_queue = queue.Queue()
        self.send_thread = None

    def start(self):
        """Starts the receiving and sending threads."""
        if self.running:
            log.warning("UDP Manager already running.")  # Use logger
            return

        log.info("Starting UDP Manager...")  # Use logger
        self.running = True

        # Start receiver thread
        self.receive_thread = threading.Thread(target=self._receive_loop, daemon=True, name="UDPReceiveThread")  # Added name
        self.receive_thread.start()
        log.info("Receiver thread started.")  # Use logger

        # Start sender thread
        self.send_thread = threading.Thread(target=self._send_loop, daemon=True, name="UDPSendThread")  # Added name
        self.send_thread.start()
        log.info("Sender thread started.")  # Use logger

    def stop(self):
        """Stops the receiving and sending threads."""
        log.info("Stopping UDP Manager...")  # Use logger
        self.running = False
        self.send_queue.put(None)  # Signal sender thread to exit

        if self.receive_thread and self.receive_thread.is_alive():
            self.receive_thread.join(timeout=1.0)
        if self.send_thread and self.send_thread.is_alive():
            self.send_thread.join(timeout=1.0)

        self.socket.close()
        log.info("UDP Socket closed.")  # Use logger
        log.info("UDP Manager stopped.")  # Use logger

    def _receive_loop(self):
        """Internal loop for receiving packets."""
        self.socket.settimeout(config.SOCKET_TIMEOUT)
        while self.running:
            try:
                data, address = self.socket.recvfrom(config.RECEIVE_BUFFER_SIZE)
                if data:
                    # Only process if the packet is from the expected server
                    if address == self.server_address:
                        log.debug(f"Received {len(data)} bytes from server {address}")  # Use logger
                        self.receive_callback(data, address)
                    else:
                        log.debug(f"Ignored packet from unexpected address: {address}")  # Use logger
            except socket.timeout:
                continue  # Just loop again on timeout
            except OSError as e:
                # Handle potential errors like socket being closed
                if self.running:
                    log.error(f"Socket error during receive: {e}")  # Use logger
                break  # Exit loop if socket is closed or error occurs
            except Exception as e:
                log.exception(f"Unexpected error in receive loop: {e}")  # Use logger
                time.sleep(0.1)  # Avoid busy-waiting on unexpected errors

    def _send_loop(self):
        """Internal loop for sending packets from the queue."""
        while self.running:
            try:
                # Wait for an item to be put in the queue
                item = self.send_queue.get()
                if item is None:  # Shutdown signal
                    break

                data, target_address = item
                log.debug(f"Sending {len(data)} bytes to {target_address}")  # Use logger
                self.socket.sendto(data, target_address)
                self.send_queue.task_done()  # Mark task as complete
                time.sleep(0.01)  # Small delay to prevent overwhelming network/CPU

            except OSError as e:
                if self.running:
                    log.error(f"Socket error during send: {e}")  # Use logger
                break  # Exit loop if socket is closed or error occurs
            except Exception as e:
                log.exception(f"Unexpected error in send loop: {e}")  # Use logger
                # Ensure task_done is called even on error if item was retrieved
                if 'item' in locals() and item is not None:
                    try:
                        self.send_queue.task_done()
                    except ValueError:  # Might happen if task_done called twice
                        pass
                time.sleep(0.1)

    def send(self, data: bytes, address: tuple = None):
        """Adds data to the send queue to be sent asynchronously."""
        if address is None:
            address = self.server_address
        if not self.running:
            log.error("Cannot send, UDP Manager is not running.")  # Use logger
            return
        self.send_queue.put((data, address))

    def get_local_address(self):
        """Returns the local address (ip, port) the socket is bound to."""
        return self.local_address
