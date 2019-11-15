$(function(){
    // Main JS
    var navVisible = false;
    var toggle_el = $("[data-toggle]").data('toggle');

    $("[data-toggle]").click(function() {
        $(toggle_el).toggleClass("open-sidebar");
        navVisible = !navVisible;
        return false;
    });

    $("#content").click(function(){
        if (navVisible) {
            navVisible = false;
            $(toggle_el).removeClass("open-sidebar");
        }
    });
    $(window).resize(function(){
        if ($(this).width() > 800 && navVisible) {
            navVisible = false;
            $(toggle_el).removeClass("open-sidebar");
        }
    });

    var uls = $("#nav").html();
    $("#sidebar").html(uls);

});
