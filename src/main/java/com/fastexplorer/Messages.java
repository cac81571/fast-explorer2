package com.fastexplorer;

/**
 * Japanese UI copy. Code identifiers stay in English.
 */
public final class Messages {
    private Messages() {
    }

    public static final String APP_NAME = "Fast Explorer";
    public static final String SUBTITLE = "共有フォルダを、名前と基本属性だけですばやく一覧";

    public static final String BACK = "戻る";
    public static final String FORWARD = "進む";
    public static final String UP = "上へ";
    public static final String GO = "移動";
    public static final String REFRESH = "更新";
    public static final String SEARCH = "検索";
    public static final String CANCEL = "中止";
    public static final String THEME_DARK = "ダーク";
    public static final String THEME_LIGHT = "ライト";

    public static final String ADDRESS_PLACEHOLDER = "\\\\server\\share\\folder  または  D:\\data";
    public static final String INCLUDE_FILE = "含める(ファイル)";
    public static final String EXCLUDE_FILE = "除外(ファイル)";
    public static final String INCLUDE_DIR = "含める(フォルダ)";
    public static final String EXCLUDE_DIR = "除外(フォルダ)";
    public static final String INCLUDE_FILE_PLACEHOLDER = "例: *.java, report*, 仕様";
    public static final String EXCLUDE_FILE_PLACEHOLDER = "例: *.tmp, ~$*, *.bak";
    public static final String INCLUDE_DIR_PLACEHOLDER = "例: src, docs*";
    public static final String EXCLUDE_DIR_PLACEHOLDER = "例: node_modules, .git, target";
    public static final String TIP_INCLUDE_FILE = "ファイル名。空欄はすべて。カンマ・空白・セミコロン区切り。* と ? が使えます（大文字小文字は区別しません）";
    public static final String TIP_EXCLUDE_FILE = "この名前のファイルを除く。カンマ・空白・セミコロン区切り。* と ? が使えます";
    public static final String TIP_INCLUDE_DIR = "相対パス上のフォルダ名。空欄はすべて。カンマ・空白・セミコロン区切り。* と ?、src/main のようなパスも使えます";
    public static final String TIP_EXCLUDE_DIR = "このフォルダは結果から外し、検索とインデックスでも中に入りません。カンマ・空白・セミコロン区切り";
    public static final String PATH_RELATIVE = "相対";
    public static final String PATH_ABSOLUTE = "絶対";
    public static final String TIP_PATH_DISPLAY = "場所列を、検索を始めたフォルダからの相対パスかフルパスで表示します";
    public static final String MENU_COPY = "コピー";
    public static final String COPY_FULL = "フルパスをコピー";
    public static final String COPY_RELATIVE = "相対パスをコピー";
    public static final String COPY_FILE_NAME = "ファイル名をコピー";
    public static final String COPY_FOLDER_NAME = "フォルダ名をコピー";
    public static final String EXT_LABEL = "拡張子";
    public static final String EXT_PLACEHOLDER = "例: java, xml, txt";
    public static final String EXT_INCLUDE_FOLDERS = "フォルダを含める";
    public static final String EXT_PRESET_ALL = "すべて";
    public static final String EXT_PRESET_DOCS = "文書";
    public static final String EXT_PRESET_SHEETS = "表計算";
    public static final String EXT_PRESET_IMAGES = "画像";
    public static final String EXT_PRESET_SOURCE = "ソース";
    public static final String EXT_RECENT_PREFIX = "最近: ";
    public static final String TIP_EXT = "空欄ですべての拡張子。カンマ・空白・セミコロン区切り。先頭のドットは省略できます（大文字小文字は区別しません）";
    public static final String TIP_EXT_FOLDERS = "検索結果にフォルダを含める。検索ボタンを押すまで結果には反映されません";

    public static final String COL_NAME = "名前";
    public static final String COL_LOCATION = "場所";
    public static final String COL_KIND = "種類";
    public static final String COL_SIZE = "サイズ";
    public static final String COL_MODIFIED = "更新日時";

    public static final String KIND_DIR = "フォルダ";
    public static final String KIND_FILE = "ファイル";
    public static final String KIND_LINK = "リンク";

    public static final String LOADING = "読み込み中…";
    public static final String SEARCHING = "検索中…";
    public static final String EMPTY_FOLDER = "このフォルダは空です";
    public static final String NO_HITS = "該当するファイルはありません";

    public static final String MENU_FILE = "ファイル";
    public static final String MENU_VIEW = "表示";
    public static final String MENU_HELP = "ヘルプ";
    public static final String MENU_OPEN_CONFIG = "設定フォルダを開く";
    public static final String MENU_EXIT = "終了";
    public static final String MENU_ABOUT = "バージョン情報";
    public static final String MENU_TOGGLE_THEME = "ダークモード";
    public static final String MENU_FAV_EXPORT = "お気に入りを書き出す…";
    public static final String MENU_FAV_IMPORT = "お気に入りを読み込む…";
    public static final String MENU_OPEN_FILE_CACHE = "ローカルキャッシュを開く";
    public static final String MENU_CLEAR_FILE_CACHE = "ローカルキャッシュを消去";
    public static final String MENU_CLEAR_HISTORY = "入力履歴を消去";
    public static final String MENU_INDEX_CURRENT = "このフォルダをインデックス";

    public static final String TAB_LIST = "一覧";
    public static final String TAB_SEARCH = "検索";
    public static final String TAB_INDEX = "インデックス";

    public static final String OPEN_IN_EXPLORER = "エクスプローラーで開く";
    public static final String REVEAL_IN_EXPLORER = "ファイルの場所をエクスプローラーで開く";
    public static final String OPEN_LOCAL_CACHE = "ローカルのキャッシュを開く";
    public static final String OPEN_LOCAL_CACHE_RO = "ローカルのキャッシュを読取り専用で開く";
    public static final String INDEX_BUILD = "インデックス作成";
    public static final String TIP_EXPLORER_WINDOWS = "この操作は Windows でのみ使えます";
    public static final String TIP_LOCAL_CACHE = "共有上のファイルをローカルにコピーして開きます。編集はサーバーに書き戻されません";
    public static final String TIP_LOCAL_CACHE_RO = "共有上のファイルを読取り専用のローカルコピーとして開きます";
    public static final String START_FALLBACK = "前回のフォルダを開けませんでした。ホームを表示します。";
    public static final String INDEX_BUSY = "このパスはすでにインデックス作成中です";
    public static final String CLEAR_CACHE_CONFIRM = "ローカルにコピーしたファイルを削除します。サーバー上のファイルは消えません。";
    public static final String DELETE_INDEX_CONFIRM = "このフォルダのインデックスを削除します。検索用のキャッシュからも消えます。";
    public static final String COPY_CANCELLED = "ローカルへのコピーを中止しました";

    public static final String FAVORITES = "お気に入り";
    public static final String FAV_FILTER = "お気に入りを絞り込む";
    public static final String FAV_ADD_CURRENT = "現在を追加";
    public static final String FAV_ADD_GROUP = "グループ";
    public static final String FAV_COLLAPSE_ALL = "全て折りたたむ";
    public static final String FAV_ADD_SELECTED = "お気に入りに追加";
    public static final String FAV_RENAME = "名前を変更";
    public static final String FAV_DELETE = "削除";
    public static final String FAV_OPEN = "開く";
    public static final String FAV_MOVE_TO = "移動";
    public static final String FAV_MOVE_ROOT = "最上位";
    public static final String FAV_NEW_GROUP = "新しいグループ";
    public static final String FAV_UP = "上へ";
    public static final String FAV_DOWN = "下へ";
    public static final String FAV_DELETE_GROUP = "このグループと中の項目を削除します。";
    public static final String FAV_IMPORT_CONFIRM = "現在のお気に入りを、ファイルの内容で置き換えます。";
    public static final String FAV_NONE = "該当するお気に入りはありません";

    public static final String TIP_FAV_ADD = "現在のフォルダをお気に入りに追加（選択中のグループの中にも入れられます）";
    public static final String TIP_FAV_GROUP = "グループを作る";
    public static final String TIP_FAV_COLLAPSE_ALL = "お気に入りツリーをすべて折りたたむ";
    public static final String TIP_FAV_FILTER = "表示名とパスで絞り込み";

    public static final String ERR_FAV_LOAD = "お気に入りを読めませんでした。空の一覧で起動します";
    public static final String ERR_FAV_SAVE = "お気に入りを保存できませんでした";
    public static final String ERR_FAV_MOVE = "その場所には移動できません";
    public static final String ERR_FAV_NAME = "名前を入力してください";

    public static final String ERR_NOT_FOUND = "パスが見つかりません";
    public static final String ERR_NOT_DIR = "フォルダではありません";
    public static final String ERR_DENIED = "アクセスが拒否されました";
    public static final String ERR_TIMEOUT = "タイムアウトしました。サーバーが応答していない可能性があります";
    public static final String ERR_UNREACHABLE = "サーバーに接続できません";
    public static final String ERR_IO = "読み取れません";
    public static final String ERR_REFRESH_PREFIX = "更新に失敗しました（キャッシュを表示中）: ";
    public static final String ERR_CACHE_FALLBACK = "キャッシュファイルを開けなかったため、今回の起動中だけのキャッシュを使います";
    public static final String ERR_OPEN_FILE = "ファイルを開けませんでした";
    public static final String ERR_DESKTOP = "この環境ではファイルを関連付けで開けません";
    public static final String ERR_EMPTY_PATH = "パスを入力してください";
    public static final String ERR_BAD_PATH = "パスを解釈できません";
    public static final String ERR_HEADLESS = "画面がないため GUI を起動できません。--self-check は使えます。";
    public static final String ERR_PARTIAL = "一部のフォルダを読めませんでした";

    public static final String TIP_BACK = "戻る (Alt+←)";
    public static final String TIP_FORWARD = "進む (Alt+→)";
    public static final String TIP_UP = "上のフォルダへ (Alt+↑ または Backspace)";
    public static final String TIP_REFRESH = "キャッシュを使わず再読込 (F5)";
    public static final String TIP_ADDRESS = "UNC パスまたはローカルパス (Ctrl+L)";
    public static final String TIP_SEARCH = "現在のフォルダ以下を検索";
    public static final String TIP_THEME = "ライトとダークを切り替え";
    public static final String TIP_CANCEL = "検索を中止してフォルダ一覧に戻る";

    public static final String SOURCE_CACHE = "キャッシュ";
    public static final String SOURCE_REFRESHING = "キャッシュ · 更新中";
    public static final String SOURCE_FS = "ファイルシステム";
    public static final String SOURCE_SEARCH = "検索";
    public static final String SEARCH_FROM_CACHE = "キャッシュから表示";
    public static final String SEARCH_DONE = "完了";

    public static String items(int count, long millis, String source, String detail) {
        String line = count + " 項目 · " + millis + " ms · " + source;
        if (detail != null && !detail.isEmpty()) {
            line += " · " + detail;
        }
        return line;
    }

    public static String partialRead(int errors) {
        return ERR_PARTIAL + "（" + errors + " 件）";
    }

    public static String attributeStats(int cacheHits, int reads) {
        return "属性キャッシュ " + cacheHits + " / 読取 " + reads;
    }

    public static String copying(String name, long copied, long total) {
        return "ローカルへコピー中… " + name + "  " + com.fastexplorer.ui.Formatters.size(copied)
                + " / " + com.fastexplorer.ui.Formatters.size(Math.max(copied, total));
    }

    public static String openedWritable(String path) {
        return "ローカルコピーを開きました。このコピーへの編集はサーバーに書き戻されません: " + path;
    }

    public static String openedReadOnly(String path) {
        return "読取り専用のローカルコピーを開きました: " + path;
    }

    public static String copied(int count) {
        return count + " 件をコピーしました";
    }

    public static String indexing(String path, int folders, int files, long millis) {
        return "インデックス中… " + path + " · フォルダ " + folders + " · ファイル " + files + " · " + millis + " ms";
    }
}
