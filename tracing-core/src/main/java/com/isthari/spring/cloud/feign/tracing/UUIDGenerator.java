package com.isthari.spring.cloud.feign.tracing;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Based on Cassandra time uuid generator
 * https://github.com/apache/cassandra/blob/cassandra-2.1/src/java/org/apache/cassandra/utils/UUIDGen.java
 */
public class UUIDGenerator {
	private static final long START_EPOCH = -12219292800000L;
	private static final long clockSeqAndNode = makeClockSeqAndNode();
	private static final UUIDGenerator instance = new UUIDGenerator();

	private long lastNanos;

	private UUIDGenerator() {
	}

	public static UUID createTimeUUID() {
		return new UUID(instance.createTimeSafe(), clockSeqAndNode);
	}

	private synchronized long createTimeSafe() {
		long nanosSince = (System.currentTimeMillis() - START_EPOCH) * 10000;
		if (nanosSince > lastNanos)
			lastNanos = nanosSince;
		else
			nanosSince = ++lastNanos;

		return createTime(nanosSince);
	}

	private static long createTime(long nanosSince) {
		long msb = 0L;
		msb |= (0x00000000ffffffffL & nanosSince) << 32;
		msb |= (0x0000ffff00000000L & nanosSince) >>> 16;
		msb |= (0xffff000000000000L & nanosSince) >>> 48;
		msb |= 0x0000000000001000L; // sets the version to 1.
		return msb;
	}

	private static long makeClockSeqAndNode() {
		long clock = new Random(System.currentTimeMillis()).nextLong();

		long lsb = 0;
		lsb |= 0x8000000000000000L; // variant (2 bits)
		lsb |= (clock & 0x0000000000003FFFL) << 48; // clock sequence (14 bits)
		lsb |= makeNode(); // 6 bytes
		return lsb;
	}

	private static long makeNode() {
		/*
		 * We don't have access to the MAC address but need to generate a node
		 * part that identify this host as uniquely as possible. The spec says
		 * that one option is to take as many source that identify this node as
		 * possible and hash them together. That's what we do here by gathering
		 * all the ip of this host. Note that FBUtilities.getBroadcastAddress()
		 * should be enough to uniquely identify the node *in the cluster* but
		 * it triggers DatabaseDescriptor instanciation and the UUID generator
		 * is used in Stress for instance, where we don't want to require the
		 * yaml.
		 */
		Collection<InetAddress> localAddresses = getAllLocalAddresses();
		if (localAddresses.isEmpty())
			throw new RuntimeException(
					"Cannot generate the node component of the UUID because cannot retrieve any IP addresses.");

		// ideally, we'd use the MAC address, but java doesn't expose that.
		byte[] hash = hash(localAddresses);
		long node = 0;
		for (int i = 0; i < Math.min(6, hash.length); i++)
			node |= (0x00000000000000ff & (long) hash[i]) << (5 - i) * 8;
		assert (0xff00000000000000L & node) == 0;

		// Since we don't use the mac address, the spec says that multicast
		// bit (least significant bit of the first octet of the node ID) must be
		// 1.
		return node | 0x0000010000000000L;
	}

	private static Collection<InetAddress> getAllLocalAddresses() {
		Set<InetAddress> localAddresses = new HashSet<InetAddress>();
		try {
			Enumeration<NetworkInterface> nets = NetworkInterface
					.getNetworkInterfaces();
			if (nets != null) {
				while (nets.hasMoreElements())
					localAddresses.addAll(Collections.list(nets.nextElement()
							.getInetAddresses()));
			}
		} catch (SocketException e) {
			throw new AssertionError(e);
		}
		return localAddresses;
	}

	private static byte[] hash(Collection<InetAddress> data) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("MD5");
			for (InetAddress addr : data)
				messageDigest.update(addr.getAddress());

			return messageDigest.digest();
		} catch (NoSuchAlgorithmException nsae) {
			throw new RuntimeException("MD5 digest algorithm is not available",
					nsae);
		}
	}

}
