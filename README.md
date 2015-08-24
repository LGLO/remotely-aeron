##### Proof of concept for Aeron transport for Remotely.

Alternative transport layer for [Remotely](https://github.com/oncue/remotely) that uses [Aeron](https://github.com/real-logic/Aeron) instead of Netty.
This code can be used for some unfair (Aeron transport is limited to 2MB responses by default) benchmarks.

TODO:
* add identifier to disconnect request to avoid disconnecting active clients by stale clients
* add heartbeat message from clients to server
* Add header for: muxing 'connections' on single stream, multi-term fragmentation
* use more scalaz-streams for concurrency




