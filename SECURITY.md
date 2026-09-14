# Security

The demo has intentionally public synthetic Basic-auth credentials and loopback listeners. It is not a production authentication setup. Consumers must resolve tenant identity from a trusted security context and secure Redis. Hashing a tenant ID does not grant access or guarantee anonymity. Report security problems privately to the repository owner.
