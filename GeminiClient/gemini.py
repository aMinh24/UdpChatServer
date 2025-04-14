import requests
import json
import config
import constants
import os
from dotenv import load_dotenv
from logger import log  # Import logger

load_dotenv()  # Load variables from .env file

class GeminiAPI:
    def __init__(self, api_url_base: str):  # Accept base URL
        # Load API key from environment variable
        self.api_key = os.getenv("GEMINI_API_KEY")
        self.api_url = None  # Initialize api_url

        if not self.api_key:
            log.warning("GEMINI_API_KEY not found in .env file or environment variables. AI responses will fail.")
        else:
            # Construct the full URL here
            self.api_url = f"{api_url_base}?key={self.api_key}"
            log.info(f"Gemini API URL configured: {api_url_base}?key=***")  # Log URL without showing full key

    def generate_response(self, prompt: str) -> str | None:
        """Sends prompt to Gemini API and returns the generated text response."""
        # Check if URL was successfully constructed
        if not self.api_url:
            log.error("Cannot call Gemini API because API key/URL is not configured correctly.")
            return "Sorry, I cannot process requests due to an API configuration error."

        headers = {"Content-Type": "application/json"}
        # Define the system prompt
        system_instruction_text = "Bạn là một chatbot và mọi tin nhắn bạn trả về sẽ gửi thẳng đến người dùng nên hãy gửi tin nhắn sạch sẽ không có chứa các dấu * và bạn có thể gửi emoji"

        payload = {
            "contents": [{"role": "user", "parts": [{"text": prompt}]}], # Ensure role is specified if using systemInstruction
            "systemInstruction": { # Add system instruction
                "parts": [{"text": system_instruction_text}]
            },
            "safetySettings": config.GEMINI_SAFETY_SETTINGS,
            "generationConfig": config.GEMINI_GENERATION_CONFIG
        }

        try:
            log.debug(f"Sending payload to Gemini: {json.dumps(payload, indent=2)}") # Log the full payload for debugging
            response = requests.post(self.api_url, headers=headers, data=json.dumps(payload), timeout=30)
            response.raise_for_status()  # Raise an exception for bad status codes (4xx or 5xx)

            response_data = response.json()
            log.debug(f"Gemini raw response: {response_data}")  # Use logger

            # Navigate the Gemini API response structure (this might change based on API version)
            if "candidates" in response_data and len(response_data["candidates"]) > 0:
                content = response_data["candidates"][0].get("content", {})
                parts = content.get("parts", [])
                if parts and "text" in parts[0]:
                    generated_text = parts[0]["text"].strip()
                    log.debug(f"Gemini generated text: {generated_text[:100]}...")  # Use logger
                    return generated_text
                else:
                    log.error(f"Unexpected Gemini response format (missing text part): {response_data}")  # Use logger
                    return "Sorry, I received an unexpected response format from the AI."
            elif "promptFeedback" in response_data:
                block_reason = response_data["promptFeedback"].get("blockReason")
                safety_ratings = response_data["promptFeedback"].get("safetyRatings")
                log.warning(f"Gemini content blocked. Reason: {block_reason}, Ratings: {safety_ratings}")  # Use logger
                return f"Sorry, my response was blocked due to safety settings (Reason: {block_reason})."
            else:
                log.error(f"Unexpected Gemini response format: {response_data}")  # Use logger
                return "Sorry, I encountered an issue processing the AI response."

        except requests.exceptions.RequestException as e:
            log.exception(f"Error calling Gemini API: {e}")  # Use logger
            status_code = e.response.status_code if e.response is not None else "N/A"
            log.error(f"Gemini API Status Code: {status_code}")  # Use logger
            if e.response is not None:
                try:
                    log.error(f"Gemini API Error Body: {e.response.json()}")  # Use logger
                except json.JSONDecodeError:
                    log.error(f"Gemini API Error Body: {e.response.text}")  # Use logger

            return f"Sorry, I couldn't reach the AI service (Error: {status_code})."
        except Exception as e:
            log.exception(f"An unexpected error occurred during Gemini API call: {e}")  # Use logger
            return "Sorry, an unexpected error occurred while generating the response."

# Example Usage (for testing)
if __name__ == "__main__":
    # Setup logger for standalone test
    import logging
    logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')

    api_key_from_env = os.getenv("GEMINI_API_KEY")
    if not api_key_from_env:
        log.error("Please set your GEMINI_API_KEY in the .env file to run this test.")  # Use logger
    else:
        gemini = GeminiAPI(config.GEMINI_API_URL_BASE)
        test_prompt = "Explain the concept of UDP sockets in simple terms."
        log.info(f"Sending prompt: {test_prompt}")  # Use logger
        response = gemini.generate_response(test_prompt)
        log.info(f"Gemini response:\n{response}")  # Use logger

        # test_prompt_2 = "Tell me about yourself."
        # log.info(f"\nSending prompt: {test_prompt_2}")  # Use logger
        # response_2 = gemini.generate_response(test_prompt_2)
        # log.info(f"Gemini response:\n{response_2}")  # Use logger
