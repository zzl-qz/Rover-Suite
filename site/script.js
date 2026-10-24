(function () {
  var copyBtn = document.querySelector("#copy-cmd");
  var command = document.querySelector("#quick-start-cmd");
  if (copyBtn && command) {
    copyBtn.addEventListener("click", function () {
      var text = command.innerText.replace(/\n+$/, "");
      var markDone = function () {
        copyBtn.classList.add("is-done");
        copyBtn.setAttribute("aria-label", "已复制");
        window.setTimeout(function () {
          copyBtn.classList.remove("is-done");
          copyBtn.setAttribute("aria-label", "复制命令");
        }, 2000);
      };
      var fallback = function () {
        var area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.left = "-9999px";
        document.body.appendChild(area);
        area.select();
        document.execCommand("copy");
        area.remove();
        markDone();
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(markDone).catch(fallback);
      } else {
        fallback();
      }
    });
  }

  var toggle = document.querySelector(".nav-toggle");
  var nav = document.querySelector("#site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  var roverToggle = document.querySelector("#rover-toggle");
  var roverTip = document.querySelector("#rover-tip");
  if (roverToggle && roverTip) {
    roverToggle.addEventListener("click", function () {
      var bot = document.querySelector("#rover-bot");
      var open = roverTip.hasAttribute("hidden");
      if (open) roverTip.removeAttribute("hidden");
      else roverTip.setAttribute("hidden", "");
      roverToggle.setAttribute("aria-expanded", open ? "true" : "false");
      if (!bot) return;
      bot.classList.remove("is-hit");
      void bot.offsetWidth;
      bot.classList.add("is-hit");
    });
  }

  var nodes = document.querySelectorAll(".reveal");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || !("IntersectionObserver" in window)) {
    nodes.forEach(function (el) { el.classList.add("is-in"); });
    return;
  }

  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-in");
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.12 });

  nodes.forEach(function (el) { observer.observe(el); });
})();
