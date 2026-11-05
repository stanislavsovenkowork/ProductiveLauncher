on run argv
    if (count of argv) < 2 then error "Missing fixture action."
    set actionName to item 1 of argv
    if actionName is not in {"install", "run", "stop", "audit", "remove"} then error "Invalid fixture action."
    set adminPath to item 2 of argv
    set commandText to "/usr/bin/python3 " & quoted form of adminPath & " " & quoted form of actionName
    if actionName is "install" then
        if (count of argv) is not 10 then error "Invalid fixture installation arguments."
        repeat with i from 3 to 10
            set commandText to commandText & " " & quoted form of (item i of argv)
        end repeat
    else if (count of argv) is not 2 then
        error "Unexpected fixture arguments."
    end if
    if actionName is "run" then set commandText to commandText & " </dev/null >/dev/null 2>&1 &"
    set outputText to do shell script commandText with administrator privileges
    if actionName is "run" then return "Fixture launch requested. Verify the dedicated socket and UI acknowledgement."
    return outputText
end run
