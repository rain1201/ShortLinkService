const Captcha = {
    difficulty: 2,

    async sha1(message) {
        const encoder = new TextEncoder();
        const data = encoder.encode(message);
        const hashBuffer = await crypto.subtle.digest('SHA-1', data);
        const hashArray = new Uint8Array(hashBuffer);
        return hashArray;
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
