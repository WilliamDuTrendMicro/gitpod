import http.server
import socketserver
from http import HTTPStatus


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        print("Handling request")
        self.send_response(HTTPStatus.OK)
        self.end_headers()
        self.wfile.write(b'Hello world')


httpd = socketserver.TCPServer(('', 58000), Handler)
httpd.serve_forever()
