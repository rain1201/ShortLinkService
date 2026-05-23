const Api = {
    baseUrl: '',

    async shorten(url, expireAfter, updateCode, captcha, time) {
        const params = new URLSearchParams();
        params.set('url', url);
        params.set('expireAfter', String(expireAfter));
        params.set('updateCode', updateCode);
        params.set('captcha', captcha);
        params.set('time', String(time));

        const resp = await fetch(this.baseUrl + '/shorten', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        return handleJsonResponse(resp);
    },

    async getInfo(id) {
        const resp = await fetch(this.baseUrl + '/getInfo/' + encodeURIComponent(id));
        return handleJsonResponse(resp);
    },

    async update(id, url, expireAfter, updateCode) {
        const params = new URLSearchParams();
        if (url) params.set('url', url);
        if (expireAfter !== undefined && expireAfter !== null && expireAfter !== '' && expireAfter !== -1) {
            params.set('expireAfter', String(expireAfter));
        }
        params.set('updateCode', updateCode);

        const resp = await fetch(this.baseUrl + '/update/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        return handleJsonResponse(resp);
    },

    async delete(id, updateCode) {
        const params = new URLSearchParams();
        params.set('updateCode', updateCode);

        const resp = await fetch(this.baseUrl + '/delete/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        return handleJsonResponse(resp);
    }
};

async function handleJsonResponse(resp) {
    const json = await resp.json();
    if (!resp.ok || json.code !== 0) {
        throw new Error(json.message || 'Unknown error');
    }
    return json.data;
}
