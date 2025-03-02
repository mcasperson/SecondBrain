async function getResult(key) {
    let result = null;

    do {
        result = await new Promise(res => setTimeout(res, 30000))
            .then(() => fetch('/api/results/' + encodeURIComponent(key), {
                method: 'GET',
            }))
    } while (result.status === 404)

    return result
}