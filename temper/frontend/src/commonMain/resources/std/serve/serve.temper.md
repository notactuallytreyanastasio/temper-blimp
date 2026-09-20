# Serving

`std/net` makes a request. This answers one.

Between them they are the two halves of the same thing, and neither is a
general socket API: a backend is free to implement these over something that
is not TCP at all, and nothing here hands out a file descriptor.

    @connected
    export interface Connection {

The bytes the client sent, as they arrived. Parsing them is the caller's
business -- a request line is a `String` and Temper is good at those.

      @connected
      get request(): String;

The bytes to send back, as they should arrive, headers and all. This is the
end of the connection: there is no second `respond`, and nothing to close.

      @connected
      respond(raw: String): Void;
    }

    @connected
    export interface Listener {

Wait for a client and answer the connection it opened. Blocks.

      @connected
      accept(): Connection;
    }

*Listen* takes a port and bubbles if it cannot have it, which is nearly always
because something else already has it.

    @connected
    export let listen(port: Int): Listener {
      panic()
    }
