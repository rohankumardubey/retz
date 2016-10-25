/*
 *    Retz
 *    Copyright (C) 2016 Nautilus Technologies, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
// System Profile Updater

function updateProfile(){
    id("up").innerText = "Requesting status to server ...";
    id("date").innerText = "";
    id("version").innerText = "";

    console.log("updating profile");
    fetch("/ping").then(function(response){
        var version = response.headers.get("Server");
        var date = response.headers.get("Date");
        var text = response.text();
        console.log("/ping => " + version + "/" + text + "@" + date);

        id("version").innerText = "System version: " + version;
        id("date").innerText = "Status at " + date + ":";
        return text;

    }).then(function(text) {
        id("up").innerText = "System status: " + text;
    });
}

//Helper function for selecting element by id
function id(id) {
    return document.getElementById(id);
}

updateProfile();
