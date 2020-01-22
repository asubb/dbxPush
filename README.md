Dropbox Push
=======

Application that helps you to push files into Dropbox in case:
1. You can't or don't want ot checkout some folder to your local working copy.
2. But you still want to upload files and to keep the structure of the files remotely certain way.

Here is my story and why that app appeared at all.

I keep all my photos for all that years that I live on the Earth in Dropbox. It's not the only storage (yes, I'm kinda serious about it %) ), but the main operational and most convenient for me. Dropbox I also use for variety of things, which doesn't matter, what is important that I need to have a copy on several devices. And at the same time, I connect to Dropbox photos folder from cellphones or similar devices, yes, of course to show photos, that's why they are there. Also I have a few devices that are making photos, not all of them can connect to clouds by themselves and be smart about it. And, for sure, I want to dump all photos from them to keep them safe and available. To a single location. Available 24/7/365. Everywhere where there is an internet. On top of that, I keep specific structure of the folders that describes the event and allows me to locate it fast. Folders are named like "2001 China. Beijing" and may have some internal structure if that makes sense.

So, my problem is, my laptop disk size is too small to checkout whole folder (or at least to keep it all the time in sync), so I just ignore it in Dropbox settings on most of my devices. Another problem, my photo-devices when I dump photos to laptop may contain old photos that are already in the cloud, and that's a lot of hustle to figure out which one are not there. 

The requirements I've compiled out of that problems are: 
* I want to be able to upload some files in the structure I defined locally (similar to Dropbox philosophy),
* and also I should be able to check if I have files that are already uploaded and don't upload them once again into a different folder.

But enough stories.

Prerequisites
----

In order to use that app you need:
1. Install JRE 8+. The app is written on Java. There are tons of instructions on Internet, won't provide another one.
2. Access Token from the Developer API key from your Dropbox account. In order to get that:
    1. Go to Dropbox App Console: https://www.dropbox.com/developers/apps
    2. Click "Create app" button
    3. Choose an API: "Dropbox API"
    4. Choose the type of access: "Full Dropbox"
    5. Name your app, for example "MyPush", remember the name you've given. Later on it'll be used as "Client Identifier"
    6. Hit "Create app" button
    7. On "Settings" tab generate a new access token and copy it.
    
Installation
----

1. Download it:

    ```bash
    curl -oL <PROVIDE THE LINK>
    ``` 

2. Unpack it:

    ```bash
    unzip dbxpush.zip
    ```

3. Run it:
    
    ```bash
    cd dbxpush/bin
    ./dbxpush -h
    ```

Usage
-----

The app is the command line tool. If you'll use the `-h` or `--help`, you'll see something like this:

```txt
usage: dbxpush [-a <arg>] [-c <arg>] [-h] [-l <arg>] [-r <arg>] [-s <arg>] [-t
       <arg>]

-a,--action <arg>           What to do during that run:
                            restructure - Restructure local files that
                            correspond to remote files based on the remote
                            folder structure. The one that has no pair will
                            remain untouched.
                            upload - Upload current local structure to remote
                            folder. It'll overwrite remote files with new one if
                            it's not the same locally.
                            prefetch - Prefetch all files into local cache only
                            clean-local - Clean cache: local files structure
                            clean-dbx - Clean cache: dropbox files structure
                            config - Store everything to config file to reuse it
                            later
-c,--client-identifier <arg>Client Identifier to access DropBox API
-h,--help                   Prints this help
-l,--local-path <arg>       Path to folder on local machine to make comparison
                            against, i.e. /users/me/Pictures
-r,--remote-path <arg>      Path to folder inside DropBox to make comparison
                            against, i.e. /photos
-s,--skip-list <arg>        Comma-separate list of files to be skipped
-t,--access-token <arg>     Token to access DropBox API
```

What you going to do is defined by `-a` or `--action` flag, you'll always can remind the list and description of the action in the help tip. Also there is a set of parameters you would need to specify like access token and so on.

**Config**

For the first run it is recommended to run a `config` action, in this case a lot of parameters will be stored into a `.properties` file in the same folder and you won't need to provide it every time:

```bash
./dbxpush --action config \
    --access-token PUT-ACCESS-TOKEN-HERE \
    --client-identifier PUT-APP-NAME \
    --local-path /path/to/your/local/files \
    --remote-path /photos/folder/on/dropbox \ 
    --skip-list .DS_Store,or-something.else,or-even.more
```

The local path should already contain all your files to upload.

**Restructure**

When the configuration part is done, you may want to check if some of your files already uploaded. If you run `restructure` action all files that uploaded will be moved according to the structure they are uploaded, that rest of the files will remain the same.

Let's take a example. 

Remote structure looks like this (relatively to your roots):

```txt
/folder1/only-remote.txt
/folder2/my-file.txt
```

And local structure looks like this:
```txt
/my-file.txt
/some-folder/new-file.txt
```

So when you run the restructure, locally you'll have the following:

```txt
/folder2/my-file.txt
/some-folder/new-file.txt
```

As you noticed, `my-file.txt` was moved according to the remote structure.

**NOTE: The file considered to be the same if they have the same name and the same content.** Content is not actually compared, used file hash provided by the Dropbox API, so don't be afraid, it is fast enough.

So the command look like this:

```bash
./dbxpush --action restructure
```

And then you'll see a lot of output how your folders are being read. The values are being cached in the local files, so you won't need to wait every time.  


**Upload**

When you're satisfied with the structure of the files and want to upload everything you've done, you can use `upload` action. It uploads files one-by-one if they are not found remotely.

```bash
./dbxpush --action upload
```

A few things to keep in mind:
1. If you moved already uploaded file, it'll upload it to a new place. Basically, you'll have two uploaded files.
2. If you changed the file which exists remotely, it'll overwrite it.

When that action is done it cleans up all local caches.