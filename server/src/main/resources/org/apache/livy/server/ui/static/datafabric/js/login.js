let signIn = function() {
    let loginUserInput = document.getElementById("loginUser");
    let loginPasswordInput = document.getElementById("loginPassword");
    let error = document.getElementById("error");

    let userName = loginUserInput.value;
    let loginPassword = loginPasswordInput.value;
    const authCred = window.btoa(`${userName}:${loginPassword}`);
    const req = new Request(window.location.origin, {
        credentials: 'include',
        headers: {
            'Content-Type': 'text/plain',
            'Authorization': `Basic ${authCred}`,
            'Request-Type': "Authentication",
        },
    });
    fetch(req).then((response) => {
        try {
            if (!response.ok) {
                error.textContent = "Invalid username or password";
                throw new Error(`Response status: ${response.status}`);
            }
            error.textContent = "";
            window.location.href = window.location.origin;
        } catch (error) {
            console.error("Error:", error);
        }
    });
}

let ssoProceed = function() {
    fetch(window.location.origin + "?action=initSSO", {
        headers: {
            'Content-Type': 'application/json',
        },
    }).then((response) => {
        if (!response.ok) {
            throw new Error(`Response status: ${response.status}`);
        }
        response.json().then(data => {
            console.log(data.loginURL);
            window.location.href = data.loginURL;
        })
    });
}

let checkRedirect = function() {
    const req = new Request(window.location.origin, {});
    fetch(req).then((response) => {
        if (response.redirected && !response.url.startsWith(window.location.origin + "/login")) {
            window.location.href = response.url;
        }
    });
}

let checkCheckSsoButton = function() {
    let ssoBtn = document.getElementById("ssoBtn");

    fetch(window.location.origin + "?action=ssoEnable", {}).then((response) => {
        if (!response.ok) {
            ssoBtn.disabled = true;
        }
    });
}

let logoutAction = function() {
    const req = new Request(window.location.origin + "?action=logout", {});
    fetch(req).then((response) => {
        if (!response.ok) {
            throw new Error(`Response status: ${response.status}`);
        }
        window.location.href = response.headers.get('Location');
    }).catch(error => {
        console.error(`Error during logout: ${error}`);
    });
}
