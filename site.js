// Plays the hero chat example once when it scrolls into view.
(function () {
    var chat = document.querySelector('.chat');
    if (!chat || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (!('IntersectionObserver' in window)) return;

    document.documentElement.classList.add('js');
    var items = chat.querySelectorAll('.bubble, .steps li');

    var observer = new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting) return;
        observer.disconnect();
        items.forEach(function (item, i) {
            setTimeout(function () { item.classList.add('is-on'); }, 300 + i * 550);
        });
    }, { threshold: 0.4 });
    observer.observe(chat);
})();
