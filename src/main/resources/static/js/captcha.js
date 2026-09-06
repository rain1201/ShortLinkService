const Captcha = {
    difficulty: 2,

    async sha1(message) {
        const encoder = new TextEncoder();
        const data = encoder.encode(message);

        // Web Crypto is unavailable on most non-secure origins. Keep it as
        // the fast path, but fall back to the local implementation so the
        // PoW challenge also works over plain HTTP/IP addresses.
        if (globalThis.crypto?.subtle) {
            try {
                const hashBuffer = await globalThis.crypto.subtle.digest('SHA-1', data);
                return new Uint8Array(hashBuffer);
            } catch (error) {
                // Some browsers expose crypto.subtle but reject digest calls
                // outside a secure context. Use the same fallback there too.
            }
        }

        return sha1PureJs(data);
    },

    isPoWValid(hash, difficulty) {
        for (let i = 0; i < difficulty; i++) {
            if (hash[i] !== 0) return false;
        }
        return true;
    },

    async solve(challenge, difficulty) {
        difficulty = difficulty || this.difficulty;
        let nonce = 0;
        while (true) {
            const hash = await this.sha1(challenge + nonce);
            if (this.isPoWValid(hash, difficulty)) {
                return String(nonce);
            }
            nonce++;
        }
    },

    buildChallenge(paramNames, paramValues, time) {
        let str = '';
        for (let i = 0; i < paramNames.length; i++) {
            str += paramNames[i] + paramValues[i];
        }
        return str + time;
    },

    async generate(paramNames, paramValues) {
        const time = Math.floor(Date.now() / 1000);
        const challenge = this.buildChallenge(paramNames, paramValues, time);
        const nonce = await this.solve(challenge, this.difficulty);
        return { captcha: nonce, time };
    }
};

// SHA-1 fallback for browsers where crypto.subtle is unavailable. The
// backend's PoW verifier uses SHA-1 and checks the raw digest bytes, so this
// returns the same Uint8Array shape as Web Crypto's digest API.
function sha1PureJs(message) {
    const bytes = message instanceof Uint8Array ? message : new Uint8Array(message);
    const bitLength = bytes.length * 8;
    const paddedLength = ((bytes.length + 9 + 63) >> 6) << 6;
    const padded = new Uint8Array(paddedLength);
    padded.set(bytes);
    padded[bytes.length] = 0x80;

    const lengthOffset = paddedLength - 8;
    for (let i = 0; i < 8; i++) {
        padded[lengthOffset + i] = Math.floor(bitLength / Math.pow(2, (7 - i) * 8)) & 0xff;
    }

    let h0 = 0x67452301;
    let h1 = 0xefcdab89;
    let h2 = 0x98badcfe;
    let h3 = 0x10325476;
    let h4 = 0xc3d2e1f0;

    const rotateLeft = (value, bits) => (value << bits) | (value >>> (32 - bits));

    for (let offset = 0; offset < padded.length; offset += 64) {
        const words = new Uint32Array(80);
        for (let i = 0; i < 16; i++) {
            const index = offset + i * 4;
            words[i] = (padded[index] << 24) |
                (padded[index + 1] << 16) |
                (padded[index + 2] << 8) |
                padded[index + 3];
        }
        for (let i = 16; i < 80; i++) {
            words[i] = rotateLeft(words[i - 3] ^ words[i - 8] ^ words[i - 14] ^ words[i - 16], 1);
        }

        let a = h0;
        let b = h1;
        let c = h2;
        let d = h3;
        let e = h4;

        for (let i = 0; i < 80; i++) {
            let f;
            let k;
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5a827999;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ed9eba1;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8f1bbcdc;
            } else {
                f = b ^ c ^ d;
                k = 0xca62c1d6;
            }

            const temp = (rotateLeft(a, 5) + f + e + k + words[i]) | 0;
            e = d;
            d = c;
            c = rotateLeft(b, 30);
            b = a;
            a = temp;
        }

        h0 = (h0 + a) | 0;
        h1 = (h1 + b) | 0;
        h2 = (h2 + c) | 0;
        h3 = (h3 + d) | 0;
        h4 = (h4 + e) | 0;
    }

    const digest = new Uint8Array(20);
    const words = [h0, h1, h2, h3, h4];
    for (let i = 0; i < words.length; i++) {
        digest[i * 4] = (words[i] >>> 24) & 0xff;
        digest[i * 4 + 1] = (words[i] >>> 16) & 0xff;
        digest[i * 4 + 2] = (words[i] >>> 8) & 0xff;
        digest[i * 4 + 3] = words[i] & 0xff;
    }
    return digest;
}
